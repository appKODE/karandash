package io.karandash.plugins.ai.providers

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Shared serialization models for OpenAI Chat Completions API.
 * These models are used by OpenAiProvider and can be reused by other
 * providers that implement OpenAI-compatible APIs (e.g., Ollama).
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * Set exactly one of [maxTokens] and [maxCompletionTokens]: OpenAI's reasoning models (o-series, gpt-5) reject
 * `max_tokens` and any non-default `temperature`, while OpenAI-compatible servers only know `max_tokens`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val temperature: Double? = null,
    @SerialName("response_format")
    val responseFormat: ResponseFormat? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    /** `ai.stream: true`: server-sent [ChatCompletionChunk]s instead of one [ChatCompletionResponse]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stream: Boolean? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("stream_options")
    val streamOptions: StreamOptions? = null,
    /** `ai.reasoningEffort`: OpenRouter's reasoning control; reasoning tokens count against the output cap. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reasoning: ReasoningOptions? = null,
)

/** OpenRouter `reasoning` object; only `effort` is set. */
@Serializable
data class ReasoningOptions(
    val effort: String,
)

/** Asks for a last chunk carrying `usage`; without it a streamed reply reports no tokens. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StreamOptions(
    @EncodeDefault
    @SerialName("include_usage")
    val includeUsage: Boolean = true,
)

/** One `data:` event of a streamed reply; the last one (with [StreamOptions.includeUsage]) has no choices, only usage. */
@Serializable
data class ChatCompletionChunk(
    val model: String = "",
    val choices: List<ChunkChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

@Serializable
data class ChunkChoice(
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val content: String? = null,
    val refusal: String? = null,
)

/**
 * Controls the output format of a completion response.
 *
 * When [type] is `"json_object"` the API guarantees the response content is valid JSON,
 * eliminating parse failures caused by prose wrapping.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ResponseFormat(
    val type: String = "json_object",
    /** Set when [type] is `"json_schema"`: the schema the reply must follow. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("json_schema")
    val jsonSchema: ResponseJsonSchema? = null,
)

/**
 * The `json_schema` payload of a [ResponseFormat]. [strict] is set only with a schema rewritten by
 * [OpenAiStrictSchema] (every property required, optional ones nullable, `additionalProperties: false`).
 */
@Serializable
data class ResponseJsonSchema(
    val name: String,
    val schema: JsonElement,
    val strict: Boolean = false,
)

@Serializable
data class ChatCompletionResponse(
    val id: String = "",
    val model: String = "",
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatResponseMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

/** The assistant reply: [content] is null when the model declines and sends a [refusal] instead. */
@Serializable
data class ChatResponseMessage(
    val role: String = "assistant",
    val content: String? = null,
    val refusal: String? = null,
)

/**
 * Token usage. OpenAI caches prompt prefixes automatically and reports only reads
 * ([PromptTokensDetails.cachedTokens]); there is no cache-write count, so cacheWriteTokens is always 0 here.
 */
@Serializable
data class ChatUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0,
    @SerialName("prompt_tokens_details")
    val promptTokensDetails: PromptTokensDetails? = null,
)

/** Breakdown of [ChatUsage.promptTokens]; OpenAI caches long prompt prefixes automatically. */
@Serializable
data class PromptTokensDetails(
    @SerialName("cached_tokens")
    val cachedTokens: Int = 0,
)
