package io.karandash.api.ai

import io.karandash.api.Module
import io.karandash.api.ModuleType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Module interface for AI/LLM providers.
 *
 * Implementations are registered via the standard module lifecycle and resolved
 * by [providerId] in configuration (e.g. `ai.provider: anthropic`).
 *
 * Third-party providers implement this interface and register via
 * `META-INF/services/io.karandash.api.Module` (JVM) or native symbol export.
 */
public interface AiProviderModule : Module {
    override val type: ModuleType get() = ModuleType.AI_PROVIDER

    /** Short lowercase identifier used in config (e.g. "openai", "anthropic"). */
    public val providerId: String

    /** Default model ID for this provider (e.g. "gpt-4o-mini", "claude-sonnet-4-20250514"). */
    public val defaultModel: String get() = ""

    /** Environment variable name for the API key (e.g. "OPENAI_API_KEY"). Null if no key needed. */
    public val apiKeyEnvVar: String? get() = null

    /** Environment variable name for the base URL (e.g. "BIFROST_BASE_URL"). Null if provider uses a fixed URL. */
    public val baseUrlEnvVar: String? get() = null

    /** Whether this provider requires an API key to function. Defaults to true when [apiKeyEnvVar] is non-null. */
    public val requiresApiKey: Boolean get() = apiKeyEnvVar != null

    /**
     * Whether [complete] reads [AiCompletionRequest.contextBlocks]. When false (the default) the host hands this
     * provider [AiCompletionRequest.withContextInPrompt], so a provider written before the field still sees the
     * whole user text in `prompt`.
     */
    public val supportsContextBlocks: Boolean get() = false

    /**
     * Send a prompt and receive a completion response.
     *
     * @param request Encapsulates prompt, system prompt, model, token limit, temperature
     * @return Completion with content and usage metadata
     * @throws AiProviderException on API errors or timeouts
     */
    public suspend fun complete(request: AiCompletionRequest): AiCompletionResponse
}

/** Encapsulates all parameters for a single AI completion call. */
@Serializable
public data class AiCompletionRequest(
    val prompt: String,
    val systemPrompt: String? = null,
    val model: String,
    val maxTokens: Int = 2000,
    val temperature: Double = 0.2,
    /** When true, instructs the provider to return guaranteed valid JSON output. */
    val jsonMode: Boolean = false,
    /**
     * JSON Schema (an object schema) the response must conform to. Providers with native schema
     * support (Anthropic forced tool use, OpenAI `response_format: json_schema`) constrain decoding to
     * it; others treat a non-null schema as [jsonMode] and the caller parses the text. Null = free-form.
     */
    val responseSchema: JsonElement? = null,
    /**
     * Stable leading parts of the user turn (MR metadata, instructions), in order, ahead of the varying [prompt]
     * (the diff). The full user text is [withContextInPrompt]'s prompt. A provider with
     * [AiProviderModule.supportsContextBlocks] sends them as separate blocks it may mark as prompt-cache
     * breakpoints; the host flattens them into [prompt] for every other provider. Redactors run over every block;
     * prompt processors see [prompt] only.
     */
    val contextBlocks: List<String> = emptyList(),
) {
    /** This request with [contextBlocks] joined in front of [prompt] by blank lines, and no blocks left. */
    public fun withContextInPrompt(): AiCompletionRequest =
        if (contextBlocks.isEmpty()) {
            this
        } else {
            copy(prompt = (contextBlocks + prompt).joinToString("\n\n"), contextBlocks = emptyList())
        }
}

/** Structured response from an AI completion call. */
@Serializable
public data class AiCompletionResponse(
    val content: String,
    /** All input tokens, cached ones included ([cacheReadTokens] and [cacheWriteTokens] are subsets). */
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val model: String,
    /** Input tokens served from the provider's prompt cache (billed at the cache-read rate). */
    val cacheReadTokens: Int = 0,
    /** Input tokens written to the provider's prompt cache (billed at the cache-write rate). */
    val cacheWriteTokens: Int = 0,
)

/** Thrown by [AiProviderModule.complete] on API errors or provider failures. */
public open class AiProviderException(
    message: String,
    public val statusCode: Int? = null,
    public val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Thrown when the provider rate-limits the request. */
    public class RateLimited(
        /** Milliseconds to wait before retrying, if provided by the API. */
        public val retryAfterMs: Long? = null,
        message: String = "Rate limited",
    ) : AiProviderException(message, statusCode = 429, retryable = true)

    /** Thrown when the API key or token is invalid or missing. */
    public class AuthenticationFailed(
        /** The provider identifier (e.g. "openai", "anthropic"). */
        public val provider: String,
        message: String = "Authentication failed for provider '$provider'",
    ) : AiProviderException(message, statusCode = 401, retryable = false)

    /** Thrown when the requested model is not available on this provider. */
    public class ModelNotAvailable(
        /** The model identifier that was requested. */
        public val model: String,
        message: String = "Model '$model' is not available",
    ) : AiProviderException(message, statusCode = 404, retryable = false)

    /** Thrown when the configured token budget has been exhausted. */
    public class TokenBudgetExhausted(
        /** Tokens consumed so far. */
        public val spent: Int,
        /** Configured token budget. */
        public val budget: Int,
        message: String = "Token budget exhausted ($spent/$budget)",
    ) : AiProviderException(message, retryable = false)
}
