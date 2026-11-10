package io.karandash.plugins.ai.providers

import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.core.AiEnvVars
import io.ktor.client.engine.HttpClientEngine

/**
 * Bifrost AI provider using the OpenAI-compatible endpoint.
 *
 * Bifrost exposes an OpenAI-compatible `/v1/chat/completions` endpoint
 * with Bearer token auth - identical wire format to [OpenAiProvider].
 * Unlike LiteLLM, Bifrost always requires an API key and a base URL
 * configured via BIFROST_BASE_URL env var or the `baseUrl` config field.
 *
 * @param bifrostApiKey Bifrost API key (from `BIFROST_API_KEY` env or config)
 * @param baseUrl Bifrost proxy base URL (required - no default)
 * @param timeout Request timeout duration
 * @param maxRetries Maximum retry attempts for transient errors
 * @param httpClientEngine Optional engine for testing with MockEngine
 */
class BifrostProvider(
    bifrostApiKey: String = "",
    baseUrl: String = "",
    timeout: KarandashDuration = DEFAULT_TIMEOUT,
    maxRetries: Int = 2,
    httpClientEngine: HttpClientEngine? = null,
) : OpenAiProvider(
        apiKey = bifrostApiKey,
        baseUrl = baseUrl,
        timeout = timeout,
        maxRetries = maxRetries,
        httpClientEngine = httpClientEngine,
    ) {
    override val providerId: String = "bifrost"

    // -- AiProviderModule (SDK) overrides --
    override val id: String = "io.karandash.ai.provider.bifrost"
    override val name: String = "Bifrost Provider"
    override val description: String = "Bifrost gateway AI provider (OpenAI-compatible)"

    override val apiKeyEnvVar: String = AiEnvVars.BIFROST_API_KEY
    override val baseUrlEnvVar: String = AiEnvVars.BIFROST_BASE_URL

    override fun spawn(
        timeout: KarandashDuration,
        maxRetries: Int,
        engine: HttpClientEngine?,
    ): AbstractAiProvider = BifrostProvider(constructorApiKey.orEmpty(), constructorBaseUrl, timeout, maxRetries, engine)

    /** `ai.baseUrl`, then `BIFROST_BASE_URL`, then the constructor value (no built-in default). */
    override val baseUrlOverrideEnvVar: String get() = baseUrlEnvVar

    companion object {
        val DEFAULT_TIMEOUT: KarandashDuration = OpenAiProvider.DEFAULT_TIMEOUT
    }
}
