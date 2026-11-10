@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.ai.AiServiceKeys
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.RuleModule
import io.karandash.api.platform.FetchOptions
import io.karandash.api.run.RunSession
import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.RuntimeModuleInitializer
import io.karandash.config.Config
import io.karandash.config.EnrichmentConfig
import io.karandash.config.RuleEnrichmentConfig
import io.karandash.config.getRuleConfigElements
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import io.karandash.utils.EnrichmentGlobMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Handles module initialization, including rule modules, AI modules,
 * and enrichment stage setup.
 *
 * Extracted from [ValidationPipeline] to isolate module lifecycle management.
 *
 * @lifecycle per-run
 */
internal class ModuleInitializer(
    private val logger: Logger,
    private val options: ValidationOptions,
) {
    private val runtimeInitializer = RuntimeModuleInitializer()

    /**
     * Initialize all rule modules that are configured in the config.
     * AI module is initialized first (if present) since `contributedRules()` needs it.
     *
     * With a [session], publishes the computed AI config ([AiServiceKeys.AI_CONFIG], `JsonNull`
     * without an `ai:` block) and tracks the AI module and every configured rule module so the run shuts them down.
     */
    suspend fun initializeConfiguredRuleModules(
        configStage: ConfigLoadStage,
        scope: io.karandash.cli.commands.validate.ValidateCommandScope,
        extensions: List<PipelineExtension>,
        session: RunSession? = null,
    ) {
        val initialized = mutableSetOf<String>()
        val configuredRuleIds = configStage.config.rules.ruleConfigs.keys
            .toMutableSet()

        val aiConfig = configStage.config.ai?.let { ai ->
            extensions.firstNotNullOfOrNull { ext -> ext.computeRuleModuleConfig(ai) } ?: ai
        }
        session?.provide(AiServiceKeys.AI_CONFIG, aiConfig ?: JsonNull)
        if (aiConfig != null) {
            val aiModule = computedConfigRuleModule(configStage)
            if (aiModule != null) {
                initialized.add(aiModule.id)
                session?.track(aiModule)
                val aiInitialized = tryInitializeAiModule(configStage, aiModule, computedConfigFor(configStage, aiModule, aiConfig))
                if (aiInitialized) {
                    // limit: refreshes the shared rule index with the AI module's config-dependent
                    // contributed rules; drop once rule lookup goes through the run
                    configStage.registry.register(aiModule)
                }
            }
        }

        if (options.enrich || configStage.config.enrichment != null) {
            configuredRuleIds += EnrichmentStage.RULE_GENERATE_DESCRIPTION
        }

        val modulesToInit = configuredRuleIds.mapNotNull { ruleId ->
            val module = scope.registry.findRuleHandler(ruleId) ?: return@mapNotNull null
            if (!initialized.add(module.id)) return@mapNotNull null
            val pluginConfig = mergedRuleModuleConfig(configStage.config, module)
            module to pluginConfig
        }
        // supervisorScope: one failing init must not cancel its siblings mid-initialize, which
        // would leave them initialized but untracked
        supervisorScope {
            modulesToInit
                .map { (module, pluginConfig) ->
                    async {
                        // tracked before init: the lease keeps a concurrent run's close from
                        // shutting the module down under this run; each run re-initializes it
                        session?.track(module)
                        initializeRuntimeModule(configStage, module, pluginConfig)
                    }
                }.awaitAll()
        }
        logger.debug("Rule modules initialized: ${modulesToInit.size} modules")
    }

    /** The rule module whose config is computed by a pipeline extension (the AI module), found by flag, not by ID. */
    private fun computedConfigRuleModule(configStage: ConfigLoadStage): RuleModule? =
        configStage.registry
            .getByType(ModuleType.RULE)
            .filterIsInstance<RuleModule>()
            .firstOrNull { it.usesExtensionComputedConfig }

    /** Computed (AI) config carries provider credentials: only built-in modules see them, guests get a redacted copy. */
    private fun computedConfigFor(
        configStage: ConfigLoadStage,
        module: Module,
        config: JsonElement,
    ): JsonElement = if (configStage.bootstrap.isBuiltIn(module)) config else withoutSecrets(config)

    private fun withoutSecrets(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(
                element
                    .filterKeys { key -> !isSecretKey(key) }
                    .mapValues { (_, value) -> withoutSecrets(value) },
            )

            is JsonArray -> JsonArray(element.map(::withoutSecrets))

            else -> element
        }

    private fun isSecretKey(key: String): Boolean {
        val normalized = key.lowercase().filter { it.isLetterOrDigit() }
        return normalized == "auth" || SECRET_KEY_SUFFIXES.any { normalized.endsWith(it) }
    }

    private suspend fun mergedRuleModuleConfig(
        config: Config,
        module: RuleModule,
    ): JsonElement {
        val merged = buildMap {
            // module-owned top-level block first: `rules:` entries win per key
            (config.extensions[module.configKey] as? JsonObject)?.let(::putAll)
            for (ruleId in module.contributedRules()) {
                val configs = config.rules.getRuleConfigElements(ruleId)
                for (ruleConfig in configs) {
                    if (ruleConfig is JsonObject) putAll(ruleConfig)
                }
            }
        }
        return JsonObject(merged)
    }

    @Suppress("TooGenericExceptionCaught") // boundary: AI module init may fail for many reasons
    private suspend fun tryInitializeAiModule(
        configStage: ConfigLoadStage,
        aiModule: Module,
        pluginConfig: JsonElement,
    ): Boolean {
        val ctx = configStage.contexts.contextWithConfig(
            moduleId = aiModule.id,
            pluginConfig = pluginConfig,
        )
        val initialized = try {
            aiModule.initialize(ctx)
        } catch (e: Exception) {
            // boundary: module initialize() may throw any exception
            e.rethrowCancellation()
            logger.warn(
                "AI module skipped: initialization failed (${e.message}). " +
                    "AI-powered rules will not run.",
            )
            return false
        }
        if (!initialized) {
            logger.warn("AI module skipped: ${aiProviderDiagnosis(configStage, pluginConfig)} AI-powered rules will not run.")
            return false
        }
        return true
    }

    /** Names the configured provider and what it needs, so the skip warning is actionable. */
    private fun aiProviderDiagnosis(
        configStage: ConfigLoadStage,
        pluginConfig: JsonElement,
    ): String {
        val providerId = ((pluginConfig as? JsonObject)?.get("provider") as? JsonPrimitive)?.contentOrNull
            ?: return "required configuration is missing for the default provider (e.g. API key or base URL not set)."
        val registry = configStage.registry
        val provider = registry.findAiProvider(providerId.lowercase())
            ?: return "provider '$providerId' is not registered (known: ${
                registry.aiProviders().joinToString(", ") { it.providerId }
            }) or could not be configured."
        val required = listOfNotNull(provider.apiKeyEnvVar?.takeIf { provider.requiresApiKey }, provider.baseUrlEnvVar)
        val hint = if (required.isEmpty()) "" else " Required: ${required.joinToString(", ")}."
        return "provider '$providerId' could not be initialized.$hint"
    }

    suspend fun initializeRuntimeModule(
        configStage: ConfigLoadStage,
        module: Module,
        pluginConfig: JsonElement,
        enrichment: EnrichmentContext = EnrichmentContext.EMPTY,
        ruleEnrichmentBindingPrefixes: Map<String, List<String>> = emptyMap(),
    ) {
        // tracked before init, like configured rules: the run's close shuts down every module it initialized
        configStage.session?.track(module)
        runtimeInitializer.initialize(configStage.contexts, module, pluginConfig, enrichment, ruleEnrichmentBindingPrefixes)
    }

    /**
     * Re-initializes rule modules that declare [RuleModule.supportedEnrichment] or have a
     * `rule-enrichment:` binding, now that enrichment providers have run and [enrichmentContext]
     * is populated.
     *
     * Mirrors the established [io.karandash.api.module.PlatformServiceModule] pattern of
     * initializing twice: once early (config-only, in [initializeConfiguredRuleModules], before
     * the MR/enrichment exist), then again here with real data. This is the only way a rule
     * module can access enrichment data via the ambient [io.karandash.api.PluginContext.enrichment]
     * property - [RuleModule.validate] takes no context parameter, and modules are forbidden from
     * retaining the [io.karandash.api.PluginContext] passed at `initialize()` beyond that call, so
     * pieces of it (like enrichment) must be captured fresh, at the point they're actually needed.
     *
     * Modules with no declared enrichment interest are left untouched - only the subset that
     * opted in via [RuleModule.supportedEnrichment] or a `rule-enrichment:` binding, and whose
     * patterns match delivered data, is re-initialized.
     */
    suspend fun deliverEnrichmentToRuleModules(
        configStage: ConfigLoadStage,
        enrichmentContext: EnrichmentContext,
        ruleEnrichmentBindings: Map<String, RuleEnrichmentConfig>,
        extensions: List<PipelineExtension>,
    ) {
        if (enrichmentContext.availableIds.isEmpty() && enrichmentContext.missingIds.isEmpty()) return

        val configuredRuleIds = configStage.config.rules.ruleConfigs.keys
        val candidates = (
            configuredRuleIds.mapNotNull { ruleId -> configStage.registry.findRuleHandler(ruleId) } +
                // The AI module is never keyed by a rule ID in `rules:` config (its rules are
                // enabled via `ai: features:` instead -- see initializeConfiguredRuleModules's
                // own special case for the same reason), so it must be added explicitly here too.
                listOfNotNull(
                    if (configStage.config.ai != null) {
                        computedConfigRuleModule(configStage)
                    } else {
                        null
                    },
                )
        ).distinctBy { it.id }

        val toReinit = mutableListOf<Triple<RuleModule, EnrichmentContext, Map<String, List<String>>>>()
        for (module in candidates) {
            val contributedIds = module.contributedRules()
            val bindingPrefixesByRule = contributedIds
                .associateWith { ruleId ->
                    ruleEnrichmentBindings[ruleId]?.enrich.orEmpty()
                }.filterValues { it.isNotEmpty() }
            val patterns = EnrichmentGlobMatcher.patternsFor(module.supportedEnrichment, bindingPrefixesByRule, contributedIds)
            val matchedIds = EnrichmentGlobMatcher.resolve(patterns, enrichmentContext.availableIds)
            // The first pass already gave every module empty enrichment: with no matched data a re-init repeats it.
            if (matchedIds.isNotEmpty()) {
                toReinit.add(Triple(module, enrichmentContext.filterIds(matchedIds), bindingPrefixesByRule))
            }
        }
        if (toReinit.isEmpty()) return

        coroutineScope {
            toReinit
                .map { (module, filtered, bindingPrefixesByRule) ->
                    async {
                        val pluginConfig = reinitPluginConfig(configStage, module, extensions)
                        reinitTolerantly(module, configStage, pluginConfig, filtered, bindingPrefixesByRule)
                    }
                }.awaitAll()
        }
        logger.debug("Re-initialized ${toReinit.size} enrichment-aware rule module(s) with real data")
    }

    /**
     * Re-initializing a module with real enrichment data is best-effort: [module] may have
     * failed its *first* initialization too (e.g. the AI module structurally exists in the
     * registry regardless of whether its provider/env vars are actually configured -- see
     * the extension-computed-config rule module in [initializeConfiguredRuleModules]), in which case re-init
     * fails for the exact same reason. [RuntimeModuleInitializer.initialize] throws
     * [ProviderException] on failure; unlike the first pass (which already handles this
     * gracefully per-module), an uncaught throw here would abort the *entire* validation run
     * for every rule, not just this one module's enrichment delivery.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: re-init of an already-optional module must not crash the run
    private suspend fun reinitTolerantly(
        module: RuleModule,
        configStage: ConfigLoadStage,
        pluginConfig: JsonElement,
        enrichment: EnrichmentContext,
        ruleEnrichmentBindingPrefixes: Map<String, List<String>> = emptyMap(),
    ) {
        try {
            initializeRuntimeModule(configStage, module, pluginConfig, enrichment, ruleEnrichmentBindingPrefixes)
        } catch (e: Exception) {
            // boundary: module initialize() is plugin code
            e.rethrowCancellation()
            logger.warn(
                "Enrichment re-init skipped for module '${module.id}': ${e.message}",
            )
        }
    }

    /**
     * Resolves the plugin config to use when re-initializing [module] with real enrichment
     * data. The AI module's config never comes from `rules:` per-rule entries (its rules are
     * enabled via `ai: features:` and its config from the top-level `ai:` block, resolved
     * through [PipelineExtension.computeRuleModuleConfig] the same way
     * [initializeConfiguredRuleModules] resolves it the first time) -- using
     * [mergedRuleModuleConfig] for it here would silently reinit it with an empty config,
     * wiping out `provider`/`baseUrl`/`features` and making it fail initialization entirely.
     *
     * Only resolves which JSON config shape to use -- per-rule `rule-enrichment:` binding
     * prefixes are a separate concern, delivered directly to [RuleModule.initialize]'s
     * two-parameter overload by [deliverEnrichmentToRuleModules]/[reinitTolerantly] instead
     * of being folded into this config.
     */
    private suspend fun reinitPluginConfig(
        configStage: ConfigLoadStage,
        module: RuleModule,
        extensions: List<PipelineExtension>,
    ): JsonElement =
        if (module.usesExtensionComputedConfig) {
            val computed = extensions.firstNotNullOfOrNull { ext -> ext.computeRuleModuleConfig(configStage.config.ai) }
                ?: requireNotNull(configStage.config.ai) {
                    "AI rule config ('ai:' block) is required when AI rules are enabled but was not found in config"
                }
            computedConfigFor(configStage, module, computed)
        } else {
            mergedRuleModuleConfig(configStage.config, module)
        }

    /**
     * Run the pre-validation enrichment stage if configured.
     */
    suspend fun runEnrichment(
        configStage: ConfigLoadStage,
        scope: io.karandash.cli.commands.validate.ValidateCommandScope,
        providerStage: ProviderStage,
        resolveModuleConfig: (Config, Map<String, JsonElement>?, Module, String?) -> JsonElement,
        fetchOptions: FetchOptions = FetchOptions(),
    ): MergeRequest {
        if (!options.enrich && configStage.config.enrichment == null) {
            logger.debug("MR title/description generation: off (no --enrich flag, no ai.enrichment block)")
            return providerStage.mr
        }

        val enrichmentConfig = configStage.config.enrichment
            ?: EnrichmentConfig(
                generateTitle = options.enrich,
                generateDescription = options.enrich,
            )

        val ci = io.karandash.detect
            .CIDetector(scope.registry.platformServices(), logger)
            .detect()
        val effectiveProjectId = options.project ?: ci?.projectId ?: ""
        val effectiveMrId = options.mrId ?: ci?.mrId ?: ""
        logger.debug(
            "Enrichment CI context: project=${effectiveProjectId.ifEmpty { "<none>" }}, mrId=${effectiveMrId.ifEmpty { "<none>" }}"
        )

        return EnrichmentStage(
            config = enrichmentConfig,
            registry = scope.registry,
            provider = providerStage.provider,
            projectId = effectiveProjectId,
            mrId = effectiveMrId,
            dryRun = options.dryRunEnrich,
            logger = logger,
            runtimeModuleInitializer = { module ->
                val pluginConfig = resolveModuleConfig(
                    configStage.config,
                    configStage.config.platforms,
                    module,
                    providerStage.provider?.configKey,
                )
                initializeRuntimeModule(configStage, module, pluginConfig)
            },
            fetchOptions = fetchOptions,
            platformHint = options.platform ?: ci?.platform,
            preferredPluginIds = scope.preferredPluginIds,
        ).enrich(providerStage.mr)
    }

    private companion object {
        /** Normalized (lowercase, alphanumeric) key suffixes treated as credentials. */
        val SECRET_KEY_SUFFIXES = listOf("apikey", "token", "password", "secret", "credentials", "privatekey", "authorization")
    }
}
