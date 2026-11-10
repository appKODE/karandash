package io.karandash.plugins.ai.providers

import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.core.AiEnvVars
import io.ktor.client.engine.HttpClientEngine

/**
 * The `custom` provider: any OpenAI-compatible endpoint at `ai.baseUrl`. Its key comes from `ai.apiKey` or
 * `CUSTOM_AI_API_KEY` only (never `OPENAI_API_KEY`); without one the request goes out unauthenticated.
 */
class CustomAiProvider(
    apiKey: String? = null,
    baseUrl: String,
    timeout: KarandashDuration = DEFAULT_TIMEOUT,
    maxRetries: Int = DEFAULT_RETRIES,
    httpClientEngine: HttpClientEngine? = null,
) : OpenAiProvider(apiKey, baseUrl, timeout, maxRetries, httpClientEngine) {
    override val providerId: String = "custom"
    override val id: String = "io.karandash.ai.provider.custom"
    override val name: String = "Custom Provider"
    override val description: String = "Custom OpenAI-compatible AI/LLM endpoint"
    override val apiKeyEnvVar: String = AiEnvVars.CUSTOM_AI_API_KEY
    override val requiresApiKey: Boolean = false

    /** The endpoint is `ai.baseUrl` itself; no env var replaces it. */
    override val baseUrlOverrideEnvVar: String? get() = null

    override fun spawn(
        timeout: KarandashDuration,
        maxRetries: Int,
        engine: HttpClientEngine?,
    ): AbstractAiProvider = CustomAiProvider(constructorApiKey, constructorBaseUrl, timeout, maxRetries, engine)

    companion object {
        /** Request timeout when `ai.timeout` is unset. */
        val DEFAULT_TIMEOUT: KarandashDuration = OpenAiProvider.DEFAULT_TIMEOUT

        /** Attempts when `ai.maxRetries` is unset. */
        const val DEFAULT_RETRIES = 2
    }
}
