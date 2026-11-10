package io.karandash.plugins.ai.providers

import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiEnvVars
import io.ktor.client.engine.HttpClientEngine

/**
 * Google Gemini AI provider using the OpenAI-compatible endpoint.
 *
 * Gemini supports the OpenAI Chat Completions API format at
 * `https://generativelanguage.googleapis.com/v1beta/openai/`.
 * API key is passed as a Bearer token.
 *
 * @param apiKey Gemini API key (from `GEMINI_API_KEY` env or config)
 * @param timeout Request timeout duration
 * @param maxRetries Maximum retry attempts for transient errors
 * @param httpClientEngine Optional engine for testing with MockEngine
 */
class GeminiProvider(
    apiKey: String = "",
    timeout: KarandashDuration = DEFAULT_TIMEOUT,
    maxRetries: Int = 2,
    httpClientEngine: HttpClientEngine? = null,
) : OpenAiProvider(
        apiKey = apiKey,
        baseUrl = DEFAULT_BASE_URL,
        timeout = timeout,
        maxRetries = maxRetries,
        httpClientEngine = httpClientEngine,
    ) {
    override val providerId: String = "gemini"

    // -- AiProviderModule (SDK) overrides --
    override val id: String = "io.karandash.ai.provider.gemini"
    override val name: String = "Gemini Provider"
    override val description: String = "Google Gemini AI/LLM provider (OpenAI-compatible)"
    override val defaultModel: String = DEFAULT_MODEL
    override val apiKeyEnvVar: String = AiEnvVars.GEMINI_API_KEY
    override val baseUrlOverrideEnvVar: String get() = AiEnvVars.GEMINI_BASE_URL

    override fun spawn(
        timeout: KarandashDuration,
        maxRetries: Int,
        engine: HttpClientEngine?,
    ): AbstractAiProvider = GeminiProvider(constructorApiKey.orEmpty(), timeout, maxRetries, engine)

    companion object {
        const val DEFAULT_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/openai"
        const val DEFAULT_MODEL = AiDefaultModels.GEMINI
        val DEFAULT_TIMEOUT: KarandashDuration = OpenAiProvider.DEFAULT_TIMEOUT
    }
}
