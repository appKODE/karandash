package io.karandash.bootstrap

import io.karandash.api.Module
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.errors.ProviderException
import io.karandash.api.module.RuleModule
import io.karandash.api.support.rethrowCancellation
import kotlinx.serialization.json.JsonElement

/**
 * Initializes a single module at runtime with a given plugin config.
 *
 * Throws [ProviderException] when initialization fails or is declined.
 * This is the generic module init logic extracted from the CLI pipeline,
 * usable by any layer that needs to initialize modules on demand.
 */
class RuntimeModuleInitializer {
    /**
     * Initialize [module] with a context from the run's [contexts] and [pluginConfig].
     *
     * @param enrichment Enrichment data to expose via [io.karandash.api.PluginContext.enrichment],
     *        already filtered to this module's declared interest. Defaults to empty.
     * @param ruleEnrichmentBindingPrefixes `rule-enrichment:` binding prefixes per rule ID,
     *        scoped to [module]'s own contributed rules. Only consumed when [module] is a
     *        [RuleModule] (via [RuleModule.initialize]'s two-parameter overload); ignored
     *        otherwise. Defaults to empty.
     * @throws ProviderException if module init fails or returns false.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: module init
    suspend fun initialize(
        contexts: RunPluginContexts,
        module: Module,
        pluginConfig: JsonElement,
        enrichment: EnrichmentContext = EnrichmentContext.EMPTY,
        ruleEnrichmentBindingPrefixes: Map<String, List<String>> = emptyMap(),
    ) {
        val ctx = contexts.contextWithConfig(
            moduleId = module.id,
            pluginConfig = pluginConfig,
            enrichment = enrichment,
        )
        val initialized = try {
            if (module is RuleModule) {
                module.initialize(ctx, ruleEnrichmentBindingPrefixes)
            } else {
                module.initialize(ctx)
            }
        } catch (e: Exception) {
            // boundary: module initialize() is plugin code
            e.rethrowCancellation()
            throw ProviderException(
                "Failed to initialize ${module.type.cliLabel} module '${module.id}': ${e.message}",
                e,
            )
        }
        if (!initialized) {
            throw ProviderException(
                "Selected ${module.type.cliLabel} module '${module.id}' is unavailable with current configuration.",
            )
        }
    }
}
