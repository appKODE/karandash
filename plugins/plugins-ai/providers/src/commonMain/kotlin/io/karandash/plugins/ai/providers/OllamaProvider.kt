package io.karandash.plugins.ai.providers

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiProviderException
import io.karandash.duration.KarandashDuration
import io.karandash.http.SsrfHostKind
import io.karandash.http.SsrfUrlValidator
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiEnvVars
import io.karandash.plugins.ai.core.config.AiOllamaConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Ollama local model provider using OpenAI-compatible API.
 * Connects to local Ollama instance (default: http://localhost:11434/v1).
 * No authentication required. Same defaults as the other providers (60s, 3 attempts); a slow local model
 * sets `ai.timeout`, or `ai.stream: true` so only silence between chunks counts.
 */
class OllamaProvider(
    baseUrl: String = "http://localhost:11434/v1",
    timeout: KarandashDuration = DEFAULT_TIMEOUT,
    maxRetries: Int = 2,
    httpClientEngine: HttpClientEngine? = null,
) : OpenAiProvider(
        apiKey = null,
        baseUrl = baseUrl,
        timeout = timeout,
        maxRetries = maxRetries,
        httpClientEngine = httpClientEngine,
    ) {
    companion object {
        private val LOCAL_HOST_KINDS = setOf(SsrfHostKind.LOOPBACK, SsrfHostKind.PRIVATE, SsrfHostKind.DOTLESS)
        private const val CONFIG_KEY = "ollama"

        /** The OpenAI-compatible endpoint of the Ollama server at [host] (`OLLAMA_HOST`, e.g. `http://localhost:11434`). */
        fun baseUrlForHost(host: String): String = "${host.trimEnd('/')}/v1"
    }

    /** Ollama is a local server by design: loopback and private hosts are reachable without `allow_localhost`. */
    override val permitsLocalHosts: Boolean = true

    override val providerId: String = "ollama"

    // -- AiProviderModule (SDK) fields --
    override val id: String = "io.karandash.ai.provider.ollama"
    override val name: String = "Ollama Provider"
    override val description: String = "Ollama local AI/LLM provider"
    override val defaultModel: String = "llama3.2"
    override val apiKeyEnvVar: String? = null

    /** `ai.ollama.allowHosts`, lowercased; set from the plugin config (the `ai:` block or a run handle's config). */
    private val allowHosts = MutableStateFlow(normalized(AiOllamaConfig.DEFAULT_ALLOW_HOSTS))

    override suspend fun onInitialize(context: PluginContext): Boolean {
        val block = (context.pluginConfig as? JsonObject)?.get(CONFIG_KEY) as? JsonObject ?: return true
        return try {
            allowHosts.value = normalized(KarandashJson.Default.decodeFromJsonElement(AiOllamaConfig.serializer(), block).allowHosts)
            true
        } catch (e: IllegalArgumentException) {
            logger.warn("Ollama provider: invalid ai.ollama config: ${e.message}")
            false
        }
    }

    private fun normalized(hosts: List<String>): Set<String> = hosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    override fun spawn(
        timeout: KarandashDuration,
        maxRetries: Int,
        engine: HttpClientEngine?,
    ): AbstractAiProvider = OllamaProvider(constructorBaseUrl, timeout, maxRetries, engine)

    /**
     * `ai.baseUrl`, then `OLLAMA_HOST` (a host, so `/v1` is appended), then the constructor value.
     * only a loopback, private-network or single-label host, or a host named in `ai.ollama.allowHosts`;
     * another remote endpoint goes through `custom`.
     */
    override fun resolveBaseUrl(): String {
        val url = configBaseUrl?.takeIf { it.isNotEmpty() }
            ?: envLookup(AiEnvVars.OLLAMA_HOST)?.let(::baseUrlForHost)
            ?: constructorBaseUrl
        if (SsrfUrlValidator.classifyHost(url) !in LOCAL_HOST_KINDS && Url(url).host.lowercase() !in allowHosts.value) {
            throw AiProviderException(
                message = "Ollama base URL $url must be a loopback or private-network host or listed in ai.ollama.allowHosts; " +
                    "use provider custom for a remote endpoint",
                retryable = false,
            )
        }
        return url
    }

    // -- Provider-specific error handling --

    override fun handleErrorStatus(
        statusCode: Int,
        bodyText: String,
    ): AiProviderException =
        when (statusCode) {
            HTTP_NOT_FOUND -> AiProviderException(
                message = "Model not found. Ensure model is pulled in Ollama.",
                statusCode = HTTP_NOT_FOUND,
                retryable = false,
            )

            else -> defaultHandleErrorStatus(statusCode, bodyText)
        }
}
