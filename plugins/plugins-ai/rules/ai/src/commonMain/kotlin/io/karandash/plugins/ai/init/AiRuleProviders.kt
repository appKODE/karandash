@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.init

import io.karandash.ai.pipeline.AiServiceImpl
import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiServiceKeys
import io.karandash.api.run.RunScopedServices
import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSpec
import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiProviderResolver
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.plugins.ai.providers.RunAiProviderSource
import io.karandash.plugins.ai.providers.withResponseChain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Providers the AI rule runs on; a runtime holder, not config. */
internal class RuleProviders(
    val provider: AiProvider,
    val namedProviders: Map<String, AiProvider>,
    val aiService: AiServiceImpl,
)

/**
 * Sources the AI rule's providers. Inside a validation run the base providers are the run's
 * handles from [RunAiProviderSource] (owned and shut down by the run); outside one (LSP, unit
 * tests) they come from [AiProviderResolver]. Either way the rule adds its own model fallback,
 * response cache and consensus on top of the main provider.
 */
internal class AiRuleProviders(
    private val registry: ModuleRegistry,
    private val resolver: AiProviderResolver,
) {
    suspend fun build(
        context: PluginContext,
        pluginCfg: AiPluginConfig,
        apiKey: String?,
    ): RuleProviders? {
        val providerName = pluginCfg.provider
        val source = runSource()
        val base = if (source != null) {
            source.baseProvider(null) ?: run {
                val baseUrl = (context.pluginConfig as? JsonObject)?.get("baseUrl")?.jsonPrimitive?.contentOrNull
                context.warn(resolver.unresolvedMessage(providerName, baseUrl != null))
                null
            }
        } else {
            resolver.resolve(providerName, apiKey, context)
        } ?: return null
        val named = buildNamedProviders(context, pluginCfg) { name, type ->
            if (source != null) source.baseProvider(name) else resolver.resolveNamed(type, context)
        }
        val aiService = AiServiceImpl(
            registry = registry,
            defaultProviderId = providerName,
            debugLogger = { msg -> context.logger.debug(msg) },
            // Config-built providers (e.g. `custom`) and named `providers:` entries are not in the
            // registry under that name; hand them to the service (a run source resolves both itself).
            localProviders = if (source == null) localProviders(providerName, base, named) else emptyMap(),
            providerSource = source,
            logger = context.logger,
        )
        return RuleProviders(buildProvider(base, context, pluginCfg), named, aiService)
    }

    private fun localProviders(
        providerName: String,
        base: AiProvider,
        named: Map<String, AiProvider>,
    ): Map<String, AiProviderModule> {
        val main = if (registry.findAiProvider(providerName) == null && base is AiProviderModule) {
            mapOf(providerName to base)
        } else {
            emptyMap()
        }
        return named.mapNotNull { (name, p) -> (p as? AiProviderModule)?.let { name to it } }.toMap() + main
    }

    /** The run's provider source once the run has published this rule's `ai:` config. */
    private suspend fun runSource(): RunAiProviderSource? {
        val services = RunScopedServices.current() ?: return null
        if (services[AiServiceKeys.AI_CONFIG] !is JsonObject) return null
        return services[AiServiceKeys.PROVIDER_SOURCE] as? RunAiProviderSource
    }

    private fun buildProvider(
        baseProvider: AiProvider,
        context: PluginContext,
        pluginCfg: AiPluginConfig,
    ): AiProvider {
        context.logger.debug(
            "AI buildProvider: base=${baseProvider.providerId} (${baseProvider::class.simpleName})",
        )

        val cm = context.cacheProvider as? CacheManager
        val aiCache = if (cm != null) {
            cm.getOrCreate<String, AiResponse>(
                RunAiProviderSource.RESPONSE_CACHE_NAME,
                CacheSpec(maxEntries = cm.memoryMaxEntries(RunAiProviderSource.RESPONSE_CACHE_NAME), tier = CacheTier.MEMORY_ONLY),
            )
        } else {
            TieredCache(
                name = RunAiProviderSource.RESPONSE_CACHE_NAME,
                maxEntries = RunAiProviderSource.DEFAULT_RESPONSE_CACHE_SIZE,
                tier = CacheTier.MEMORY_ONLY,
            )
        }
        return baseProvider.withResponseChain(
            cfg = pluginCfg,
            cache = aiCache,
            logger = context.logger,
            onFallback = { m, err -> context.warn("AI plugin: Model '$m' failed ($err), trying next fallback...") },
        )
    }

    private suspend fun buildNamedProviders(
        context: PluginContext,
        pluginCfg: AiPluginConfig,
        resolveNamed: suspend (name: String, type: String) -> AiProvider?,
    ): Map<String, AiProvider> =
        pluginCfg.providers.entries
            .mapNotNull { (name, providerType) ->
                val type = (providerType as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (type == null) {
                    context.warn("AI plugin: providers['$name'] must be a provider type string; entry ignored.")
                    return@mapNotNull null
                }
                val p = resolveNamed(name, type)
                if (p == null) {
                    context.warn("AI plugin: providers['$name'] type '$type' could not be resolved; entry ignored.")
                    return@mapNotNull null
                }
                name to p
            }.toMap()
}
