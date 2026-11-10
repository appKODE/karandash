package io.karandash.plugins.ai.init

import io.karandash.ai.pipeline.AiServiceImpl
import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.context.EnrichmentContext
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.plugins.ai.AiRuntimeContext
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiEnvVars
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.DiffMode
import io.karandash.plugins.ai.core.DismissedEntry
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.config.ExecutionProfileResolver
import io.karandash.plugins.ai.core.performance.PgoProfile
import io.karandash.plugins.ai.prompts.PromptResolver
import io.karandash.plugins.ai.prompts.PromptSource
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiProviderResolver
import io.karandash.plugins.ai.providers.CachingAiProvider
import io.karandash.plugins.ai.rules.CustomAiRule
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

/**
 * Result of a successful [AiRuleInitializer.initialize] call.
 *
 * Contains every resolved configuration value needed to construct the module's
 * `InitState`. Returned as `null` when initialization should abort (missing env
 * vars, no enabled rules, provider resolution failure).
 *
 * Not @Serializable: runtime context holder, holds a non-serializable `EnrichmentContext`.
 */
internal data class InitResult(
    val runtimeContext: AiRuntimeContext,
    val provider: AiProvider?,
    val tokenBudget: TokenBudget?,
    val enabledRules: Set<String>,
    val model: String,
    val failOnError: Boolean,
    val pluginCfg: AiPluginConfig,
    val aiRuleConfig: io.karandash.plugins.ai.core.rules.AiRuleConfig,
    val promptSources: Map<String, PromptSource>,
    val promptResolver: PromptResolver,
    val concurrencySemaphore: Semaphore,
    val perRuleModels: Map<String, String>,
    val perRuleTemperatures: Map<String, Double>,
    val normalizedRuleProviders: Map<String, String>,
    val strictness: ReviewStrictness,
    val customRules: List<CustomAiRule>,
    val namedProviders: Map<String, AiProvider>,
    val providerResolver: AiProviderResolver,
    val perRuleBudgets: Map<String, TokenBudget>,
    val aiService: AiServiceImpl?,
    val feedbackStore: FeedbackStore,
    val statePersistence: AiStatePersistence?,
    val cachingProvider: CachingAiProvider?,
    val pluginStartMark: TimeSource.Monotonic.ValueTimeMark?,
    val enrichment: EnrichmentContext = EnrichmentContext.EMPTY,
    val ruleEnrichmentBindingPrefixes: Map<String, List<String>> = emptyMap(),
)

/**
 * Resolved per-rule prompt/model/temperature/provider maps.
 *
 * Destructured via `component1..5` in [AiRuleInitializer.initialize].
 *
 * Not @Serializable: runtime context holder, holds a non-serializable `PromptResolver`.
 */
internal data class PerRuleMaps(
    val promptSources: Map<String, PromptSource>,
    val promptResolver: PromptResolver,
    val perRuleModels: Map<String, String>,
    val perRuleTemperatures: Map<String, Double>,
    val normalizedRuleProviders: Map<String, String>,
)

/**
 * Extracts all initialization logic from [io.karandash.plugins.ai.AiRuleModule].
 *
 * Resolves configuration, builds providers, evaluates enabled rules, and
 * assembles an [InitResult] that the module stores atomically.
 *
 * @param registry Module registry for provider/analyzer lookups.
 * @param injectedPersistence Externally-injected state persistence (may be null).
 */
internal class AiRuleInitializer(
    private val registry: ModuleRegistry,
    private val injectedPersistence: AiStatePersistence?,
) {
    /**
     * Runs the full initialization pipeline.
     *
     * @return [InitResult] on success, or `null` when the module should report
     *   `initialize() = false` (missing env vars, no enabled rules, provider failure).
     */
    suspend fun initialize(
        context: PluginContext,
        ruleEnrichmentBindingPrefixes: Map<String, List<String>> = emptyMap(),
    ): InitResult? {
        val pluginCfg = resolvePluginConfig(context)
        val providerName = pluginCfg.provider
        val providerResolver = AiProviderResolver(registry)
        val providerModule = registry.findAiProvider(providerName)

        val missingEnvVars = buildMissingEnvVarsList(providerModule, context, pluginCfg)
        if (missingEnvVars.isNotEmpty()) {
            context.warn(
                "AI module skipped: required environment variable(s) not set: " +
                    "${missingEnvVars.joinToString(", ")}. " +
                    "AI-powered rules will not run.",
            )
            return null
        }

        val model = pluginCfg.model
            ?: providerModule?.defaultModel?.ifEmpty { null }
            ?: DEFAULT_MODEL
        val aiRuleConfig = io.karandash.plugins.ai.core.rules.AiRuleConfig
            .fromPluginConfig(context.pluginConfig, context.logger)
        val feedbackStore = resolveFeedbackStore(context, pluginCfg) ?: return null
        val customRules = CustomAiRule.fromPluginConfig(context.pluginConfig) { context.warn(it) }
        val knownRuleIds = registry.aiAnalyzers().map { it.analyzerId } + customRules.map { it.id } + AiRuleIds.CHECKS
        val strictness = ReviewStrictness.fromString(pluginCfg.strictness)

        val enabledRules = buildEnabledRules(context, pluginCfg, customRules)
        if (enabledRules.isEmpty()) {
            context.warn("AI plugin: All features are disabled.")
            return null
        }

        val providers = AiRuleProviders(registry, providerResolver).build(context, pluginCfg, pluginCfg.apiKey) ?: return null
        // After the last early return: the prompt resolver may own an HTTP client, closed by the module's shutdown.
        val perRuleMaps = resolvePerRuleMaps(pluginCfg, model, context, knownRuleIds)
        val provider = providers.provider
        val cachingProvider = provider as? CachingAiProvider
        val statePersistence = resolveStatePersistence(context)
        val concurrency = resolveConcurrency(context, pluginCfg)
        val namedProviders = providers.namedProviders
        val aiService = providers.aiService
        val budgets = AiRuleBudgets(registry).build(enabledRules, pluginCfg, context) { toRuleId(it, knownRuleIds) }

        logInitSummary(context, providerName, model, enabledRules, pluginCfg, strictness, perRuleMaps)
        val pluginStartMark = TimeSource.Monotonic.markNow()
        if (pluginCfg.pgoEnabled) {
            PgoProfile.enableProfiling()
        }

        return InitResult(
            runtimeContext = AiRuntimeContext.from(context),
            provider = provider,
            tokenBudget = budgets.run,
            enabledRules = enabledRules,
            model = model,
            failOnError = pluginCfg.failOnError,
            pluginCfg = pluginCfg,
            aiRuleConfig = aiRuleConfig,
            promptSources = perRuleMaps.promptSources,
            promptResolver = perRuleMaps.promptResolver,
            concurrencySemaphore = Semaphore(concurrency),
            perRuleModels = perRuleMaps.perRuleModels,
            perRuleTemperatures = perRuleMaps.perRuleTemperatures,
            normalizedRuleProviders = perRuleMaps.normalizedRuleProviders,
            strictness = strictness,
            customRules = customRules,
            namedProviders = namedProviders,
            providerResolver = providerResolver,
            perRuleBudgets = budgets.perRule,
            aiService = aiService,
            feedbackStore = feedbackStore,
            statePersistence = statePersistence,
            cachingProvider = cachingProvider,
            pluginStartMark = pluginStartMark,
            enrichment = context.enrichment,
            ruleEnrichmentBindingPrefixes = ruleEnrichmentBindingPrefixes,
        )
    }

    private fun resolvePluginConfig(context: PluginContext): AiPluginConfig {
        var cfg = AiPluginConfig.fromPluginConfig(context.pluginConfig, context.logger)
        if (!cfg.dryRunAi) {
            val envDryRun = context.env(AiEnvVars.KARANDASH_AI_DRY_RUN)
            if (envDryRun?.lowercase() in setOf("true", "1", "yes")) {
                cfg = cfg.copy(dryRunAi = true)
            }
        }
        val envMaxTokens = context
            .env(AiEnvVars.KARANDASH_AI_MAX_TOKEN_BUDGET)
            ?.toIntOrNull()
        if (envMaxTokens != null && envMaxTokens > 0) {
            cfg = cfg.copy(maxTokenBudget = envMaxTokens)
        }
        val envReviewMode = context.env(AiEnvVars.KARANDASH_REVIEW_MODE)
        if (envReviewMode != null) {
            cfg = cfg.copy(reviewMode = DiffMode.fromString(envReviewMode))
        }
        return cfg
    }

    private fun resolveFeedbackStore(
        context: PluginContext,
        pluginCfg: AiPluginConfig,
    ): FeedbackStore? {
        if (!pluginCfg.feedbackEnabled) return FeedbackStore()
        val rawFeedback = (context.pluginConfig as? JsonObject)?.get("feedback")
        // Object form keeps the dismissed findings under `dismissed`; the list form is the entries itself.
        val feedbackData = (if (rawFeedback is JsonObject) rawFeedback["dismissed"] else rawFeedback)
            ?: return FeedbackStore()
        return try {
            FeedbackStore.fromEntries(
                Json.decodeFromJsonElement(ListSerializer(DismissedEntry.serializer()), feedbackData),
            )
        } catch (e: SerializationException) {
            context.warn("AI module disabled: malformed 'feedback' config: ${e.message}")
            null
        }
    }

    private suspend fun resolvePerRuleMaps(
        pluginCfg: AiPluginConfig,
        model: String,
        context: PluginContext,
        knownRuleIds: List<String>,
    ): PerRuleMaps {
        val toRuleId = { key: String -> toRuleId(key, knownRuleIds) }
        val promptSources = PromptSourceMerger.merge(pluginCfg, context, toRuleId)
        return PerRuleMaps(
            promptSources = promptSources,
            promptResolver = PromptResolver(PromptSourceMerger.remoteFetcher(promptSources.values, context), context.logger),
            perRuleModels = pluginCfg.models.entries.associate { (k, v) ->
                toRuleId(k) to v.ifEmpty { model }
            },
            perRuleTemperatures = pluginCfg.temperatures.entries.associate { (k, v) ->
                toRuleId(k) to v
            },
            normalizedRuleProviders = pluginCfg.ruleProviders.entries.associate { (k, v) ->
                toRuleId(k) to v
            },
        )
    }

    private fun resolveStatePersistence(context: PluginContext): AiStatePersistence? {
        val persistence = injectedPersistence ?: return null
        val cleaned = persistence.cleanupExpired()
        if (cleaned > 0) {
            context.log("AI plugin: Cleaned up $cleaned expired AI state entries")
        }
        return persistence
    }

    private fun buildMissingEnvVarsList(
        providerModule: AiProviderModule?,
        context: PluginContext,
        pluginCfg: AiPluginConfig,
    ): List<String> {
        if (providerModule == null) return emptyList()
        val missing = mutableListOf<String>()
        val apiKeyEnv = providerModule.apiKeyEnvVar
        if (apiKeyEnv != null && providerModule.requiresApiKey) {
            val hasApiKey = context.env(apiKeyEnv)?.isNotEmpty() == true ||
                pluginCfg.apiKey?.isNotEmpty() == true
            if (!hasApiKey) missing.add(apiKeyEnv)
        }
        val baseUrlEnv = providerModule.baseUrlEnvVar
        if (baseUrlEnv != null) {
            val cfg = context.pluginConfig as? JsonObject ?: JsonObject(emptyMap())
            val configBaseUrl = cfg["baseUrl"]?.jsonPrimitive?.contentOrNull
            val hasBaseUrl = context.env(baseUrlEnv)?.isNotEmpty() == true ||
                configBaseUrl?.isNotEmpty() == true
            if (!hasBaseUrl) missing.add(baseUrlEnv)
        }
        return missing
    }

    private fun buildEnabledRules(
        context: PluginContext,
        pluginCfg: AiPluginConfig,
        customRules: List<CustomAiRule>,
    ): Set<String> {
        val hasExplicitEnables = pluginCfg.features.values.any { it.jsonPrimitive.booleanOrNull == true }
        val hasExplicitDisables = pluginCfg.features.values.any { it.jsonPrimitive.booleanOrNull == false }
        val defaultForBuiltins = !(hasExplicitEnables && !hasExplicitDisables)

        val registryAnalyzers = registry.aiAnalyzers()
        if (registryAnalyzers.isEmpty()) {
            context.warn(
                "No AI analyzer modules registered in ModuleRegistry. " +
                    "AI validation requires analyzer modules to be listed in builtin-modules.txt.",
            )
        }

        val builtRules = buildSet {
            for (analyzer in registryAnalyzers) {
                val default = if (analyzer.defaultEnabled) defaultForBuiltins else false
                if (pluginCfg.isFeatureEnabled(analyzer.featureKey, default, context.pluginConfig)) {
                    add(analyzer.analyzerId)
                }
            }
            customRules.forEach { add(it.id) }
            if (pluginCfg.checks.isNotEmpty() || pluginCfg.checksTicketCompliance) add(AiRuleIds.CHECKS)
        }
        if (!defaultForBuiltins) warnDefaultsTurnedOff(context, registryAnalyzers, builtRules)
        return applyExecutionProfile(builtRules, pluginCfg, context)
    }

    /** an allowlist of `true` features turns every other default analyzer off; say which ones. */
    private fun warnDefaultsTurnedOff(
        context: PluginContext,
        analyzers: List<AiAnalyzerModule>,
        enabled: Set<String>,
    ) {
        val off = analyzers.filter { it.defaultEnabled && it.analyzerId !in enabled }.map { it.analyzerId }.sorted()
        if (off.isEmpty()) return
        context.warn(
            "AI plugin: only the analyzers set to true in ai.features run, so these default analyzers are turned off: " +
                "${off.joinToString()}. Set them to true to keep them.",
        )
    }

    private fun applyExecutionProfile(
        rules: Set<String>,
        pluginCfg: AiPluginConfig,
        context: PluginContext,
    ): Set<String> {
        val profileName = pluginCfg.executionProfile ?: return rules
        val profile = ExecutionProfileResolver.resolve(profileName, pluginCfg.executionProfiles)
            ?: return rules
        val ignored = ((context.pluginConfig as? JsonObject)?.get("executionProfiles") as? JsonObject)
            ?.get(profileName)
            ?.let { it as? JsonObject }
            ?.keys
            .orEmpty()
            .intersect(ExecutionProfileResolver.NO_EFFECT_KEYS)
        if (profile.description.isNotBlank()) context.log("AI plugin: execution profile '$profileName': ${profile.description}")
        if (ignored.isNotEmpty()) {
            context.warn("AI plugin: execution profile '$profileName' sets ${ignored.joinToString()}, which have no effect")
        }
        if (!profile.aiEnabled) {
            context.log("AI plugin: execution profile '$profileName' turns AI off -- no analyzers run")
            return emptySet()
        }
        val filtered = ExecutionProfileResolver.filterRules(profile, rules)
        if (filtered != rules) {
            context.log("AI plugin: execution profile '$profileName' -- ${filtered.size} rules enabled")
        }
        return filtered
    }

    private fun resolveConcurrency(
        context: PluginContext,
        pluginCfg: AiPluginConfig,
    ): Int {
        val envValue = context.env(AiEnvVars.AI_CONCURRENCY)
            ?: context.env(AiEnvVars.CORE_CONCURRENCY)
        return envValue?.toIntOrNull()?.coerceIn(1, AiPluginConfig.MAX_CONCURRENCY)
            ?: pluginCfg.concurrency.coerceIn(1, AiPluginConfig.MAX_CONCURRENCY)
    }

    private fun logInitSummary(
        context: PluginContext,
        providerName: String,
        model: String,
        enabledRules: Set<String>,
        pluginCfg: AiPluginConfig,
        strictness: ReviewStrictness,
        perRuleMaps: PerRuleMaps,
    ) {
        context.log(
            "AI plugin initialized: provider=$providerName, model=$model, rules=$enabledRules, " +
                "tokenBudget=${pluginCfg.maxTokenBudget}, strictness=$strictness",
        )
        if (pluginCfg.scopes.isNotEmpty()) {
            val scopeNames = pluginCfg.scopes.joinToString(", ") { "${it.name} (${it.paths.size} patterns)" }
            context.logger.debug("AI plugin: ${pluginCfg.scopes.size} scope(s) configured: $scopeNames")
        }
        if (perRuleMaps.promptSources.isNotEmpty()) {
            for ((ruleId, source) in perRuleMaps.promptSources) {
                context.logger.debug("AI plugin: prompt source for $ruleId = ${source::class.simpleName}")
            }
        }
    }

    companion object {
        private const val DEFAULT_MODEL = AiDefaultModels.GENERIC
        private val UPPERCASE_LETTER_REGEX = Regex("([A-Z])")

        private fun String.normalizeRuleId(): String = replace(UPPERCASE_LETTER_REGEX) { "-${it.value.lowercase()}" }

        /** Exact rule-id lookups downstream: resolve each key to the rule id it names in any spelling. */
        fun toRuleId(
            key: String,
            knownRuleIds: List<String>,
        ): String = ModuleIdNormalizer.resolve(key, knownRuleIds) ?: key.normalizeRuleId()
    }
}

/** `features.<name>` wins, then a top-level `<name>` flag in [rawConfig], then [default]. */
internal fun AiPluginConfig.isFeatureEnabled(
    featureName: String,
    default: Boolean,
    rawConfig: JsonElement,
): Boolean =
    features[featureName]?.jsonPrimitive?.booleanOrNull
        ?: ((rawConfig as? JsonObject)?.get(featureName) as? JsonPrimitive)?.booleanOrNull
        ?: default
