package io.karandash.plugins.ai.providers

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiProviderModule
import io.karandash.plugins.ai.core.AiPluginConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolves [AiProvider] instances by name via the module registry.
 *
 * For registry providers, calls [AbstractAiProvider.configure] with config-resolved
 * apiKey and baseUrl before initialization so that the provider can use these
 * values at runtime (instead of relying solely on constructor defaults).
 *
 * @param registry Optional module registry for resolving providers by name.
 */
class AiProviderResolver(
    private val registry: ModuleRegistry? = null,
) {
    private companion object {
        private const val PROVIDER_CUSTOM = "custom"

        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_TIMEOUT = "timeout"
        private const val KEY_MAX_RETRIES = "maxRetries"
        private const val KEY_STRUCTURED_OUTPUT = "structuredOutput"
    }

    /**
     * Resolve the primary AI provider from the registry, with special handling for
     * the "custom" provider type which requires a [baseUrl] from config.
     *
     * @param providerName Provider type name (e.g. "openai", "anthropic", "ollama", "custom", "bifrost")
     * @param apiKey API key for providers that require authentication
     * @param context Plugin context for config and env access
     * @return The resolved [AiProvider], or null if resolution fails
     */
    suspend fun resolve(
        providerName: String,
        apiKey: String?,
        context: PluginContext,
    ): AiProvider? {
        val name = providerName.lowercase()
        val cfg = context.pluginConfig as? JsonObject ?: JsonObject(emptyMap())
        val configBaseUrl = cfg[KEY_BASE_URL]?.jsonPrimitive?.contentOrNull

        val configTimeout = cfg[KEY_TIMEOUT]?.jsonPrimitive?.contentOrNull?.let { parseAiTimeout(it, context.logger::warn) }
        val configMaxRetries = cfg[KEY_MAX_RETRIES]?.jsonPrimitive?.intOrNull
        val structuredOutput = (cfg[KEY_STRUCTURED_OUTPUT] as? JsonPrimitive)?.booleanOrNull
        // the same knobs a run handle gets from RunAiProviderSource, so a rule outside a run sends the same request
        val ai = AiPluginConfig.fromPluginConfig(cfg)
        val configureKnobs: suspend AbstractAiProvider.() -> Unit = {
            configure(
                apiKey = null,
                baseUrl = null,
                structuredOutput = structuredOutput,
                stream = ai.stream.takeIf { it },
                streamDeadline = ai.streamDeadline.takeIf { ai.stream },
                streamStall = ai.streamStall.takeIf { ai.stream },
                promptCacheTtl = ai.promptCacheTtl,
                reasoningEffort = ai.reasoningEffort,
            )
        }

        val registryProvider = registry?.findAiProvider(name)
        if (registryProvider != null) {
            context.logger.debug(
                "AI resolver: found registry provider '${registryProvider.providerId}' " +
                    "(${registryProvider::class.simpleName}), configuring with apiKey=${apiKey != null}, " +
                    "baseUrl=$configBaseUrl, timeout=$configTimeout"
            )
            if (registryProvider is AbstractAiProvider) {
                registryProvider.configure(
                    apiKey = apiKey,
                    baseUrl = configBaseUrl,
                    timeout = configTimeout,
                    maxRetries = configMaxRetries,
                )
                registryProvider.configureKnobs()
            }
            if (registryProvider.initialize(context)) {
                return registryProvider.asAiProvider()
            }
            context.warn(unresolvedMessage(name, configBaseUrl != null))
        } else if (name != PROVIDER_CUSTOM) {
            context.warn(unresolvedMessage(name, configBaseUrl != null))
        }

        if (name == PROVIDER_CUSTOM) {
            if (configBaseUrl == null) {
                context.warn(unresolvedMessage(name, hasBaseUrl = false))
                return null
            }
            val customTimeout = configTimeout ?: CustomAiProvider.DEFAULT_TIMEOUT
            val maxRetries = cfg[KEY_MAX_RETRIES]?.jsonPrimitive?.intOrNull ?: CustomAiProvider.DEFAULT_RETRIES
            return CustomAiProvider(
                apiKey = apiKey,
                baseUrl = configBaseUrl,
                timeout = customTimeout,
                maxRetries = maxRetries,
            ).also { it.configureKnobs() }
        }

        return null
    }

    /** Why [providerName] could not be resolved: failed init, unknown name or `custom` without a base URL. */
    fun unresolvedMessage(
        providerName: String,
        hasBaseUrl: Boolean,
    ): String {
        val name = providerName.lowercase()
        val provider = registry?.findAiProvider(name)
        return when {
            provider != null -> {
                "AI plugin: provider '${provider.providerId}' failed to initialize. " + requiredSettingsHint(provider)
            }

            name != PROVIDER_CUSTOM -> {
                val known = (registry?.aiProviders().orEmpty().map { it.providerId } + PROVIDER_CUSTOM).sorted()
                "AI plugin: unknown AI provider '$name'. Known providers: ${known.joinToString(", ")}."
            }

            !hasBaseUrl -> {
                "AI plugin: Custom provider requires 'baseUrl' in plugin config."
            }

            else -> {
                "AI plugin: Custom provider failed to initialize."
            }
        }
    }

    private fun requiredSettingsHint(provider: AiProviderModule): String {
        val required = listOfNotNull(
            provider.apiKeyEnvVar?.takeIf { provider.requiresApiKey }?.let { "$it (or ai.apiKey)" },
            provider.baseUrlEnvVar?.let { "$it (or ai.$KEY_BASE_URL)" },
        )
        return if (required.isEmpty()) {
            "No required environment variables are declared; check the provider's endpoint."
        } else {
            "Required: ${required.joinToString(", ")}."
        }
    }

    /**
     * Resolve a named provider for per-rule provider composition via the registry.
     *
     * Named providers resolve apiKey and baseUrl from environment variables only
     * (no config-level values are injected).
     *
     * @param providerType Provider type name
     * @param context Plugin context for env access
     * @return The resolved [AiProvider], or null if the provider is not registered
     */
    suspend fun resolveNamed(
        providerType: String,
        context: PluginContext,
    ): AiProvider? {
        val registryProvider = registry?.findAiProvider(providerType.lowercase())
        if (registryProvider != null && registryProvider.initialize(context)) {
            return registryProvider.asAiProvider()
        }
        return null
    }
}

/**
 * Wraps an [AiProviderModule] as an [AiProvider].
 *
 * Used when the registry returns an [AiProviderModule] that does not
 * also implement [AiProvider] directly (e.g. third-party or test stubs).
 */
internal fun AiProviderModule.asAiProvider(): AiProvider =
    if (this is AiProvider) {
        this
    } else {
        object : AiProvider {
            override val providerId: String = this@asAiProvider.providerId

            override suspend fun complete(
                prompt: String,
                systemPrompt: String?,
                model: String,
                maxTokens: Int,
                temperature: Double,
                jsonMode: Boolean,
            ): AiResponse = completeRequest(AiCompletionRequest(prompt, systemPrompt, model, maxTokens, temperature, jsonMode))

            override suspend fun completeRequest(request: AiCompletionRequest): AiResponse {
                val module = this@asAiProvider
                val response = module.complete(if (module.supportsContextBlocks) request else request.withContextInPrompt())
                return AiResponse(
                    content = response.content,
                    promptTokens = response.promptTokens,
                    completionTokens = response.completionTokens,
                    totalTokens = response.totalTokens,
                    model = response.model,
                    cacheReadTokens = response.cacheReadTokens,
                    cacheWriteTokens = response.cacheWriteTokens,
                )
            }

            override suspend fun close() {
                // AiProviderModule lifecycle is managed externally; shutdown() is not called here
            }
        }
    }
