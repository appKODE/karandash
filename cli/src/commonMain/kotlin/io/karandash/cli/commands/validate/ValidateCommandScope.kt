@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.commands.validate

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.cache.KarandashCache
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.run.RunContext
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.provider.ProviderResolver
import io.karandash.cli.provider.ProviderRuntime
import io.karandash.conditions.DefaultPolicyEngine
import io.karandash.config.Config
import io.karandash.config.PolicyConfig
import io.karandash.config.getRuleConfigElements
import io.karandash.config.pipeline.isModuleDisabled
import io.karandash.engine.PolicyOptions
import io.karandash.engine.ValidationEngine
import io.karandash.sdk.Logger

/**
 * Encapsulates the dependency lifecycle for a single validate command run.
 *
 * Holds [registry] and plugin [bootstrap], creates [ValidationEngine] instances,
 * and cleanly shuts down loaded plugins on [cleanup].
 *
 * Built-in modules (rules, reporters, providers) are loaded automatically by
 * [PluginBootstrap] via [io.karandash.bootstrap.BuiltInPluginLoader] - no
 * manual registration is required here.
 *
 * @param config The parsed configuration
 * @param logger Logger for operational messages
 * @param existingRegistry Optional pre-existing registry (for two-phase loading).
 *                         If null, creates a new registry and bootstraps plugins.
 * @param existingBootstrap Optional pre-existing bootstrap (for two-phase loading).
 *                          Must be provided if existingRegistry is provided.
 */
internal class ValidateCommandScope(
    private val config: Config,
    private val logger: Logger,
    existingRegistry: ModuleRegistry? = null,
    existingBootstrap: PluginBootstrap? = null
) {
    val registry: ModuleRegistry = existingRegistry ?: ModuleRegistryImpl(logger)
    val preferredPluginIds: Set<String> = buildSet {
        config.platforms?.keys?.let { addAll(it) }
        config.reporters
            ?.filterValues { !isModuleDisabled(it) }
            ?.keys
            ?.let { addAll(it) }
    }

    private val resolver = ProviderResolver()
    internal val providerRuntime = ProviderRuntime(resolver)

    private var bootstrap: PluginBootstrap? = existingBootstrap
    private val ownsBootstrap = (existingBootstrap == null)

    /** Bootstrap for the main validate path (includes providers, reports load result). */
    suspend fun bootstrapMain() {
        if (bootstrap == null) {
            bootstrap = ValidateRuntimeBootstrap(logger).bootstrapMain(registry)
        }
    }

    /** Bootstrap for the watch-mode re-validation path (no providers, silent). */
    suspend fun bootstrapWatch() {
        if (bootstrap == null) {
            bootstrap = ValidateRuntimeBootstrap(logger).bootstrapWatch(registry)
        }
    }

    /** Create a [ValidationEngine] wired to this scope's [registry] and [config]. */
    fun createEngine(
        enabledModules: Map<String, Boolean> = emptyMap(),
        parsedPolicies: List<PolicyConfig> = emptyList(),
        envLookup: (String) -> String? = { null },
        ciPlatform: String? = null,
        isCI: Boolean = false,
        patternExpander: ((String) -> String)? = null,
        enrichmentContext: EnrichmentContext = EnrichmentContext.EMPTY,
        run: RunContext? = null,
        ruleCache: KarandashCache? = null,
        platform: PlatformServiceModule? = null,
        invalidatedRules: Set<String> = emptySet(),
    ): ValidationEngine =
        ValidationEngine(
            config = config,
            registry = registry,
            configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            enabledModules = enabledModules,
            policy = PolicyOptions(
                engine = if (parsedPolicies.isNotEmpty()) {
                    config.limits.let {
                        val expressions = it.expressions
                        DefaultPolicyEngine(
                            logger,
                            patternExpander,
                            expressions.effectiveMaxLength,
                            expressions.effectiveMaxNestingDepth,
                            it.regex.effectiveMaxPatternLength,
                        )
                    }
                } else {
                    null
                },
                policies = parsedPolicies,
                envLookup = envLookup,
                ciPlatform = ciPlatform,
                isCI = isCI,
                patternExpander = patternExpander,
            ),
            logger = logger,
            enrichmentContext = enrichmentContext,
            run = run,
            ruleCache = ruleCache,
            platform = platform,
            invalidatedRules = invalidatedRules,
        )

    /** Cleanly shuts down loaded plugins. */
    suspend fun cleanup() {
        // Only shutdown if we created the bootstrap (not provided externally)
        if (ownsBootstrap) {
            bootstrap?.shutdown()
            bootstrap = null
        }
    }
}
