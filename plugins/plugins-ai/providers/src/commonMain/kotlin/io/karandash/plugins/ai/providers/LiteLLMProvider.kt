package io.karandash.plugins.ai.providers

import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiEnvVars
import io.ktor.client.engine.HttpClientEngine

/**
 * LiteLLM AI provider using the OpenAI-compatible endpoint.
 *
 * LiteLLM exposes an OpenAI-compatible `/v1/chat/completions` endpoint
 * with Bearer token auth - identical wire format to [OpenAiProvider].
 * Can run locally without authentication (like Ollama).
 *
 * @param apiKey LiteLLM API key (from `LITELLM_API_KEY` env or config), empty for unauthenticated
 * @param baseUrl LiteLLM proxy base URL (default `http://localhost:4000/v1`)
 * @param timeout Request timeout duration
 * @param maxRetries Maximum retry attempts for transient errors
 * @param httpClientEngine Optional engine for testing with MockEngine
 */
class LiteLLMProvider(
    apiKey: String = "",
    baseUrl: String = DEFAULT_BASE_URL,
    timeout: KarandashDuration = DEFAULT_TIMEOUT,
    maxRetries: Int = 2,
    httpClientEngine: HttpClientEngine? = null,
) : OpenAiProvider(
        apiKey = apiKey,
        baseUrl = baseUrl,
        timeout = timeout,
        maxRetries = maxRetries,
        httpClientEngine = httpClientEngine,
    ) {
    override val providerId: String = "litellm"

    // -- AiProviderModule (SDK) overrides --
    override val id: String = "io.karandash.ai.provider.litellm"
    override val name: String = "LiteLLM Provider"
    override val description: String = "LiteLLM proxy AI provider (OpenAI-compatible)"
    override val defaultModel: String = AiDefaultModels.OPENAI
    override val apiKeyEnvVar: String = AiEnvVars.LITELLM_API_KEY
    override val baseUrlEnvVar: String = AiEnvVars.LITELLM_BASE_URL
    override val requiresApiKey: Boolean = false

    override fun spawn(
        timeout: KarandashDuration,
        maxRetries: Int,
        engine: HttpClientEngine?,
    ): AbstractAiProvider = LiteLLMProvider(constructorApiKey.orEmpty(), constructorBaseUrl, timeout, maxRetries, engine)

    /** `ai.baseUrl`, then `LITELLM_BASE_URL`, then the constructor value (which defaults to [DEFAULT_BASE_URL]). */
    override val baseUrlOverrideEnvVar: String get() = baseUrlEnvVar

    override val defaultBaseUrl: String get() = constructorBaseUrl.ifEmpty { DEFAULT_BASE_URL }

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:4000/v1"
        val DEFAULT_TIMEOUT: KarandashDuration = OpenAiProvider.DEFAULT_TIMEOUT
    }
}
