package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiProviderException
import io.karandash.api.support.rethrowCancellation
import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiEnvVars
import io.karandash.plugins.ai.core.ReasoningEffortOverride
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.parseUrl
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI-compatible AI provider using Ktor HTTP client.
 * Supports the /chat/completions endpoint with retry logic for transient errors.
 *
 * Implements both [AiProvider] (internal analyzer API) and AbstractAiProvider
 * (SDK module for registry-based discovery).
 */
open class OpenAiProvider(
    apiKey: String? = null,
    baseUrl: String = "https://api.openai.com/v1",
    timeout: KarandashDuration = DEFAULT_TIMEOUT,
    maxRetries: Int = 2,
    httpClientEngine: HttpClientEngine? = null,
) : AbstractAiProvider(
        timeout = timeout,
        maxRetries = maxRetries,
        httpClientEngine = httpClientEngine,
    ) {
    /** API key from the constructor (takes precedence over config/env). */
    override val constructorApiKey: String? = apiKey

    /** Base URL from the constructor (default when `ai.baseUrl` is not configured). */
    protected val constructorBaseUrl: String = baseUrl

    override val providerId: String = "openai"

    // -- AiProviderModule (SDK) fields --
    override val id: String = "io.karandash.ai.provider.openai"
    override val name: String = "OpenAI Provider"
    override val description: String = "OpenAI-compatible AI/LLM provider"
    override val defaultModel: String = AiDefaultModels.OPENAI
    override val apiKeyEnvVar: String? = AiEnvVars.OPENAI_API_KEY

    override fun spawn(
        timeout: KarandashDuration,
        maxRetries: Int,
        engine: HttpClientEngine?,
    ): AbstractAiProvider = OpenAiProvider(constructorApiKey, constructorBaseUrl, timeout, maxRetries, engine)

    override val defaultBaseUrl: String get() = constructorBaseUrl

    override val baseUrlOverrideEnvVar: String? get() = AiEnvVars.OPENAI_BASE_URL

    // -- Provider-specific HTTP request --

    override suspend fun sendRequest(
        prompt: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        temperature: Double,
        jsonMode: Boolean,
    ): HttpResponse =
        post(
            chatRequest(prompt, systemPrompt, model, maxTokens, temperature, reasoningEffort())
                .copy(responseFormat = if (jsonMode) ResponseFormat("json_object") else null),
        )

    /**
     * Native structured output: `response_format: json_schema` constrains the reply to the schema, with
     * `strict: true` on api.openai.com when the schema has a strict form ([OpenAiStrictSchema]). Another
     * OpenAI-compatible host gets the schema (non-strict) only when `ai.structuredOutput` is set to true;
     * left unset it gets `response_format: json_object`, so the reply is still JSON.
     */
    override suspend fun sendStructuredRequest(request: AiCompletionRequest): HttpResponse {
        if (!isOpenAiHost() && configStructuredOutput != true) return sendCompletion(request.copy(jsonMode = true))
        val schema = checkNotNull(request.responseSchema) { "structured request without a schema" }
        val strict = strictSchema(schema)
        val format = ResponseFormat(
            type = "json_schema",
            jsonSchema = ResponseJsonSchema(STRUCTURED_SCHEMA_NAME, strict ?: schema, strict = strict != null),
        )
        val base =
            chatRequest(
                request.withContextInPrompt().prompt,
                request.systemPrompt,
                request.model,
                request.maxTokens,
                request.temperature,
                reasoningEffort(),
            )
        return post(base.copy(responseFormat = format))
    }

    override suspend fun completeRequest(request: AiCompletionRequest): AiResponse {
        val response = super.completeRequest(request)
        val schema = request.responseSchema ?: return response
        return if (strictSchema(schema) != null) response.copy(content = OpenAiStrictSchema.dropNulls(response.content)) else response
    }

    private fun strictSchema(schema: JsonElement): JsonElement? = if (isOpenAiHost()) OpenAiStrictSchema.of(schema) else null

    private fun isOpenAiHost(): Boolean = parseUrl(resolveBaseUrl())?.host == OPENAI_HOST

    /**
     * A [ReasoningEffortOverride] over `ai.reasoningEffort`; api.openai.com rejects OpenRouter's `reasoning` field,
     * so the override is never sent there.
     */
    private suspend fun reasoningEffort(): String? =
        ReasoningEffortOverride.current()?.takeUnless { isOpenAiHost() } ?: configReasoningEffort

    private fun chatRequest(
        prompt: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        temperature: Double,
        reasoningEffort: String?,
    ): ChatCompletionRequest {
        val messages = buildList {
            if (systemPrompt != null) {
                add(ChatMessage(role = "system", content = systemPrompt))
            }
            add(ChatMessage(role = "user", content = prompt))
        }
        val base = ChatCompletionRequest(model = model, messages = messages, reasoning = reasoningEffort?.let(::ReasoningOptions))
        return if (isOpenAiReasoningModel(model)) {
            base.copy(maxCompletionTokens = maxTokens)
        } else {
            base.copy(maxTokens = maxTokens, temperature = temperature)
        }
    }

    /**
     * api.openai.com only: OpenAI-compatible servers reuse model names but keep the classic parameters. A `-chat`
     * variant (`gpt-5-chat-latest`) is a chat model that accepts `temperature`.
     */
    private fun isOpenAiReasoningModel(model: String): Boolean {
        val name = model.lowercase()
        return isOpenAiHost() && CHAT_VARIANT_MARKER !in name && REASONING_MODEL_PREFIXES.any { name.startsWith(it) }
    }

    @Suppress("TooGenericExceptionCaught") // Ktor HTTP boundary
    private suspend fun post(request: ChatCompletionRequest): HttpResponse {
        val effectiveApiKey = resolveApiKey()
        val effectiveBaseUrl = resolveBaseUrl()

        return try {
            httpClient.post("${effectiveBaseUrl.trimEnd('/')}/chat/completions") {
                contentType(ContentType.Application.Json)
                if (effectiveApiKey != null) {
                    bearerAuth(effectiveApiKey)
                }
                setBody(if (streaming) request.copy(stream = true, streamOptions = StreamOptions()) else request)
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
    }

    // -- Provider-specific response parsing --

    override fun parseSuccessResponse(
        bodyText: String,
        statusCode: Int,
        durationMs: Long,
        headers: Map<String, String>,
    ): AiResponse {
        val response = try {
            json.decodeFromString<ChatCompletionResponse>(bodyText)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw AiProviderException(
                message = "Failed to parse response: ${e.message}",
                statusCode = statusCode,
                cause = e,
            )
        }

        val choice = response.choices.firstOrNull()
            ?: throw AiProviderException("No choices returned in response")
        if (choice.finishReason == FINISH_LENGTH) {
            warnTruncated(response.model, response.usage?.completionTokens ?: 0, noContent = choice.message.content.isNullOrBlank())
        }
        choice.message.refusal?.let { warnRefusal(response.model, it) }

        return AiResponse(
            content = choice.message.content.orEmpty(),
            promptTokens = response.usage?.promptTokens ?: 0,
            completionTokens = response.usage?.completionTokens ?: 0,
            totalTokens = response.usage?.totalTokens ?: 0,
            model = response.model,
            durationMs = durationMs,
            cacheReadTokens = response.usage?.promptTokensDetails?.cachedTokens ?: 0,
            truncated = choice.finishReason == FINISH_LENGTH,
        )
    }

    /** One [ChatCompletionChunk]; the usage arrives on the last one ([StreamOptions.includeUsage]). */
    override fun decodeStreamEvent(data: String): AiChunk? {
        val chunk = try {
            json.decodeFromString<ChatCompletionChunk>(data)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw AiProviderException(message = "Failed to parse stream chunk: ${e.message}", cause = e)
        }
        val choice = chunk.choices.firstOrNull()
        val usage = chunk.usage
        return AiChunk(
            text = choice?.delta?.content.orEmpty(),
            promptTokens = usage?.promptTokens,
            completionTokens = usage?.completionTokens,
            cacheReadTokens = usage?.promptTokensDetails?.cachedTokens,
            model = chunk.model.ifEmpty { null },
            refusal = choice?.delta?.refusal,
            // The usage usually comes on a later chunk: the warning waits for the merged reply.
            truncated = choice?.finishReason == FINISH_LENGTH,
        )
    }

    // -- Provider-specific error handling --

    override fun handleErrorStatus(
        statusCode: Int,
        bodyText: String,
    ): AiProviderException = defaultHandleErrorStatus(statusCode, bodyText)

    companion object {
        /** Default request timeout for OpenAI-compatible providers. */
        val DEFAULT_TIMEOUT: KarandashDuration = KarandashDuration.ofSeconds(60)
        private const val STRUCTURED_SCHEMA_NAME = "response"
        private const val FINISH_LENGTH = "length"
        private const val OPENAI_HOST = "api.openai.com"

        /** o1, o3, o4-mini, gpt-5 and later: `max_completion_tokens` only, default temperature only. */
        private const val CHAT_VARIANT_MARKER = "-chat"
        private val REASONING_MODEL_PREFIXES = listOf("o1", "o3", "o4", "gpt-5")
    }
}
