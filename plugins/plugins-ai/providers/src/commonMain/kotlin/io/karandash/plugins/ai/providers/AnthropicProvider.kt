package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiProviderException
import io.karandash.api.support.rethrowCancellation
import io.karandash.duration.KarandashDuration
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiCostEstimator
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiEnvVars
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Anthropic Claude AI provider using Ktor HTTP client.
 * Supports the /messages endpoint with retry logic for transient errors.
 */
class AnthropicProvider(
    apiKey: String = "",
    baseUrl: String = "https://api.anthropic.com/v1",
    timeout: KarandashDuration = DEFAULT_TIMEOUT,
    maxRetries: Int = 2,
    httpClientEngine: HttpClientEngine? = null,
) : AbstractAiProvider(
        timeout = timeout,
        maxRetries = maxRetries,
        httpClientEngine = httpClientEngine,
    ) {
    override val constructorApiKey: String = apiKey
    private val constructorBaseUrl: String = baseUrl

    private companion object {
        private const val HTTP_ANTHROPIC_OVERLOADED = 529
        private val DEFAULT_TIMEOUT = KarandashDuration.ofSeconds(120)
        private const val API_VERSION = "2023-06-01"
        private const val FIRST_FIXED_TEMPERATURE_MAJOR = 5
        private val CLAUDE_FAMILIES = setOf("opus", "sonnet", "haiku")
        private const val TEXT_BLOCK = "text"
        private const val TOOL_USE_BLOCK = "tool_use"
        private const val STRUCTURED_TOOL = "respond"
        private const val STRUCTURED_TOOL_DESCRIPTION = "Return the complete answer as this tool's input."
        private const val STOP_MAX_TOKENS = "max_tokens"
        private const val STOP_REFUSAL = "refusal"
        private const val OVERLOADED_ERROR = "overloaded_error"

        /** `message_delta` does not repeat the model; its stop-reason log says this instead. */
        private const val STREAMED_MODEL = "(streamed)"

        /** The Messages API accepts at most four `cache_control` breakpoints per request. */
        private const val MAX_CACHE_BREAKPOINTS = 4

        /**
         * Claude 5.x and later fix sampling: Opus rejects any `temperature`, Sonnet/Haiku reject
         * non-default values. Matches `claude-<family>-<major>...` with major >= 5.
         */
        fun rejectsTemperature(model: String): Boolean {
            val parts = model.split('-')
            if (parts.size < 3 || parts[0] != "claude" || parts[1] !in CLAUDE_FAMILIES) return false
            val major = parts[2].toIntOrNull() ?: return false
            return major >= FIRST_FIXED_TEMPERATURE_MAJOR
        }
    }

    override val providerId: String = "anthropic"

    // -- AiProviderModule (SDK) fields --
    override val id: String = "io.karandash.ai.provider.anthropic"
    override val name: String = "Anthropic Provider"
    override val description: String = "Anthropic Claude AI/LLM provider"
    override val defaultModel: String = AiDefaultModels.ANTHROPIC
    override val apiKeyEnvVar: String = AiEnvVars.ANTHROPIC_API_KEY

    override fun spawn(
        timeout: KarandashDuration,
        maxRetries: Int,
        engine: HttpClientEngine?,
    ): AbstractAiProvider = AnthropicProvider(constructorApiKey, constructorBaseUrl, timeout, maxRetries, engine)

    override val defaultBaseUrl: String get() = constructorBaseUrl

    override val baseUrlOverrideEnvVar: String get() = AiEnvVars.ANTHROPIC_BASE_URL

    // -- Provider-specific HTTP request --

    override suspend fun sendRequest(
        prompt: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        temperature: Double,
        jsonMode: Boolean,
    ): HttpResponse = sendCompletion(AiCompletionRequest(prompt, systemPrompt, model, maxTokens, temperature, jsonMode))

    override suspend fun sendCompletion(request: AiCompletionRequest): HttpResponse {
        val systemPrompt = request.systemPrompt
        val effectiveSystemPrompt = if (request.jsonMode) {
            val suffix = "\n\nRespond with ONLY valid JSON, no surrounding text."
            when {
                systemPrompt == null -> suffix.trimStart()
                systemPrompt.contains(suffix.trim()) -> systemPrompt
                else -> systemPrompt + suffix
            }
        } else {
            systemPrompt
        }
        return post(baseRequest(request.copy(systemPrompt = effectiveSystemPrompt)))
    }

    /**
     * Native structured output: the schema becomes the input schema of a single tool the model is
     * forced to call (`tool_choice`), so the answer arrives as a schema-shaped `tool_use` input.
     */
    override suspend fun sendStructuredRequest(request: AiCompletionRequest): HttpResponse {
        val schema = checkNotNull(request.responseSchema) { "structured request without a schema" }
        return post(
            baseRequest(request).copy(
                tools = listOf(AnthropicTool(STRUCTURED_TOOL, STRUCTURED_TOOL_DESCRIPTION, schema)),
                toolChoice = AnthropicToolChoice(type = "tool", name = STRUCTURED_TOOL),
            ),
        )
    }

    /** `ai.promptCacheTtl: 1h` asks for the 1-hour entry; anything else keeps the API's 5-minute default. */
    private fun cacheControl(): AnthropicCacheControl =
        AnthropicCacheControl(ttl = configPromptCacheTtl?.takeIf { it == AiCostEstimator.PROMPT_CACHE_TTL_1H })

    /**
     * The system prompt and the stable context blocks ahead of the diff are the cache prefix: the system prompt is
     * one breakpoint, and the last context blocks take the rest of the API's [MAX_CACHE_BREAKPOINTS]. The prompt
     * itself (the diff) is never a breakpoint.
     */
    private fun baseRequest(request: AiCompletionRequest): AnthropicRequest {
        val system = request.systemPrompt
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(AnthropicTextBlock(text = it, cacheControl = cacheControl())) }
        val blocks = request.contextBlocks.filter { it.isNotBlank() }
        val content = if (blocks.isEmpty()) {
            JsonPrimitive(request.prompt)
        } else {
            val firstMarked = blocks.size - (MAX_CACHE_BREAKPOINTS - system.orEmpty().size)
            val userBlocks = blocks.mapIndexed { index, text ->
                AnthropicTextBlock(text = text, cacheControl = cacheControl().takeIf { index >= firstMarked })
            } + AnthropicTextBlock(text = request.prompt)
            KarandashJson.Default.encodeToJsonElement(ListSerializer(AnthropicTextBlock.serializer()), userBlocks)
        }
        return AnthropicRequest(
            model = request.model,
            maxTokens = request.maxTokens,
            system = system,
            messages = listOf(AnthropicMessage(role = "user", content = content)),
            temperature = request.temperature.takeUnless { rejectsTemperature(request.model) },
        )
    }

    @Suppress("TooGenericExceptionCaught") // Ktor HTTP boundary
    private suspend fun post(request: AnthropicRequest): HttpResponse =
        try {
            httpClient.post("${resolveBaseUrl().trimEnd('/')}/messages") {
                contentType(ContentType.Application.Json)
                header("x-api-key", resolveApiKey().orEmpty())
                header("anthropic-version", API_VERSION)
                setBody(if (streaming) request.copy(stream = true) else request)
            }
        } catch (e: HttpRequestTimeoutException) {
            throw e
        } catch (e: Exception) {
            // boundary: HTTP provider call; wrapped into AiProviderException
            e.rethrowCancellation()
            throw AiProviderException(
                message = "HTTP request failed: ${e.message}",
                retryable = true,
                cause = e,
            )
        }

    // -- Provider-specific response parsing --

    override fun parseSuccessResponse(
        bodyText: String,
        statusCode: Int,
        durationMs: Long,
        headers: Map<String, String>,
    ): AiResponse {
        val response = try {
            json.decodeFromString<AnthropicResponse>(bodyText)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw AiProviderException(
                message = "Failed to parse response: ${e.message}",
                statusCode = statusCode,
                cause = e,
            )
        }

        // a structured call answers in the forced tool's input; otherwise a thinking model puts
        // `thinking` blocks before the answer and only `text` blocks carry it
        val toolInput = response.content.firstOrNull { it.type == TOOL_USE_BLOCK && it.name == STRUCTURED_TOOL }?.input
        val textBlocks = response.content.filter { it.type == TEXT_BLOCK }
        if (toolInput == null && textBlocks.isEmpty()) {
            throw AiProviderException(
                "No content: no text blocks returned in response (stop_reason=${response.stopReason})",
            )
        }
        logStopReason(response.stopReason, response.model, response.usage.outputTokens)

        val usage = response.usage
        // input_tokens excludes cached tokens: the full prompt is the sum of all three
        val promptTokens = usage.inputTokens + usage.cacheCreationInputTokens + usage.cacheReadInputTokens
        return AiResponse(
            content = toolInput?.toString() ?: textBlocks.joinToString("") { it.text },
            promptTokens = promptTokens,
            completionTokens = usage.outputTokens,
            totalTokens = promptTokens + usage.outputTokens,
            model = response.model,
            durationMs = durationMs,
            cacheReadTokens = usage.cacheReadInputTokens,
            cacheWriteTokens = usage.cacheCreationInputTokens,
            truncated = response.stopReason == STOP_MAX_TOKENS,
        )
    }

    private fun logStopReason(
        reason: String?,
        model: String,
        outputTokens: Int,
    ) {
        when (reason) {
            STOP_MAX_TOKENS -> logger.warn(
                "[ai] anthropic model=$model stopped at max_tokens " +
                    "($outputTokens output tokens): the response is truncated, raise the analyzer cap (ai.<analyzer-id>.max-tokens)",
            )

            STOP_REFUSAL -> logger.warn("[ai] anthropic model=$model refused the request (stop_reason=refusal)")

            else -> logger.debug("[ai] anthropic model=$model stop_reason=$reason")
        }
    }

    /**
     * Messages API stream events: `message_start` carries the model and input usage, `content_block_delta` the text
     * (or the forced tool's `partial_json`), `message_delta` the stop reason and output tokens, `error` a failure.
     */
    override fun decodeStreamEvent(data: String): AiChunk? {
        val event = try {
            json.decodeFromString<AnthropicStreamEvent>(data)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw AiProviderException(message = "Failed to parse stream event: ${e.message}", cause = e)
        }
        return when (event.type) {
            "message_start" -> {
                event.message?.let { message ->
                    val usage = message.usage
                    AiChunk(
                        promptTokens = usage?.let { it.inputTokens + it.cacheCreationInputTokens + it.cacheReadInputTokens },
                        completionTokens = usage?.outputTokens,
                        cacheReadTokens = usage?.cacheReadInputTokens,
                        cacheWriteTokens = usage?.cacheCreationInputTokens,
                        model = message.model.ifEmpty { null },
                    )
                }
            }

            "content_block_delta" -> {
                (event.delta?.text ?: event.delta?.partialJson)?.let { AiChunk(text = it) }
            }

            "message_delta" -> {
                val outputTokens = event.usage?.outputTokens
                val truncated = event.delta?.stopReason == STOP_MAX_TOKENS
                // a truncated stream is warned once by the merge, like OpenAI's
                if (!truncated) logStopReason(event.delta?.stopReason, STREAMED_MODEL, outputTokens ?: 0)
                AiChunk(completionTokens = outputTokens, truncated = truncated)
            }

            "error" -> {
                throw AiProviderException(
                    message = "Anthropic stream error: ${event.error?.type}: ${event.error?.message}",
                    retryable = event.error?.type == OVERLOADED_ERROR,
                )
            }

            else -> {
                null
            }
        }
    }

    // -- Provider-specific error handling --

    override fun handleErrorStatus(
        statusCode: Int,
        bodyText: String,
    ): AiProviderException =
        when (statusCode) {
            HTTP_ANTHROPIC_OVERLOADED -> AiProviderException(
                message = "Anthropic API overloaded",
                statusCode = HTTP_ANTHROPIC_OVERLOADED,
                retryable = true,
            )

            else -> defaultHandleErrorStatus(statusCode, bodyText)
        }
}

/** Optional fields are omitted (not sent as `null`) when unset. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens")
    val maxTokens: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val system: List<AnthropicTextBlock>? = null,
    val messages: List<AnthropicMessage>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val temperature: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tools: List<AnthropicTool>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("tool_choice")
    val toolChoice: AnthropicToolChoice? = null,
    /** `ai.stream: true`: the reply comes as server-sent [AnthropicStreamEvent]s. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stream: Boolean? = null,
)

/** A text block; a non-null [cacheControl] makes everything up to and including it a cache prefix. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class AnthropicTextBlock(
    val text: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "text",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("cache_control")
    val cacheControl: AnthropicCacheControl? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class AnthropicCacheControl(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "ephemeral",
    /** Null = the 5-minute default; `1h` = the 1-hour entry (2x write price). */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ttl: String? = null,
)

@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema")
    val inputSchema: JsonElement,
)

@Serializable
internal data class AnthropicToolChoice(
    val type: String,
    val name: String,
)

@Serializable
internal data class AnthropicMessage(
    val role: String,
    /** A plain string, or an array of [AnthropicTextBlock] when the turn carries cacheable context blocks. */
    val content: JsonElement,
)

@Serializable
internal data class AnthropicResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<AnthropicContent>,
    val model: String,
    @SerialName("stop_reason")
    val stopReason: String? = null,
    @SerialName("stop_sequence")
    val stopSequence: String? = null,
    val usage: AnthropicUsage,
)

@Serializable
internal data class AnthropicContent(
    val type: String,
    val text: String = "",
    val name: String? = null,
    val input: JsonElement? = null,
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("output_tokens")
    val outputTokens: Int,
    @SerialName("cache_creation_input_tokens")
    val cacheCreationInputTokens: Int = 0,
    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: Int = 0,
)

/** One `data:` event of a streamed Messages reply; only the fields its [type] uses are set. */
@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    val message: AnthropicStreamMessage? = null,
    val delta: AnthropicStreamDelta? = null,
    val usage: AnthropicStreamUsage? = null,
    val error: AnthropicStreamError? = null,
)

@Serializable
internal data class AnthropicStreamMessage(
    val model: String = "",
    val usage: AnthropicUsage? = null,
)

@Serializable
internal data class AnthropicStreamDelta(
    val text: String? = null,
    @SerialName("partial_json")
    val partialJson: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null,
)

@Serializable
internal data class AnthropicStreamUsage(
    @SerialName("output_tokens")
    val outputTokens: Int? = null,
)

@Serializable
internal data class AnthropicStreamError(
    val type: String = "",
    val message: String = "",
)
