package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.context.ValidationContext
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.finding.FilterProvenance
import io.karandash.api.finding.FindingHashChain
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.module.ExtensionStateStore
import io.karandash.api.module.FindingsAwareRuleModule
import io.karandash.api.module.FixDismissalAware
import io.karandash.api.module.SkipReasonProvider
import io.karandash.api.module.StatePersistenceAware
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiCostEstimator
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.AiRuleStrategy
import io.karandash.plugins.ai.core.AiRuleStrategyConfig
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.AiStatePersistenceAware
import io.karandash.plugins.ai.core.DismissedEntry
import io.karandash.plugins.ai.core.ReviewInstruction
import io.karandash.plugins.ai.core.SecretRedactor
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import io.karandash.plugins.ai.core.config.ExecutionProfileResolver
import io.karandash.plugins.ai.core.config.TestGapConfig
import io.karandash.plugins.ai.core.performance.PgoProfile
import io.karandash.plugins.ai.core.rules.AiExcludePolicy
import io.karandash.plugins.ai.dispatch.AiFeedbackCostFilter
import io.karandash.plugins.ai.dispatch.AiMrPreprocessor
import io.karandash.plugins.ai.dispatch.AiPromptAssembler
import io.karandash.plugins.ai.dispatch.AiRuleDispatcher
import io.karandash.plugins.ai.dispatch.AiSkipEvaluator
import io.karandash.plugins.ai.dispatch.CanaryTracker
import io.karandash.plugins.ai.dispatch.RuleCallOverride
import io.karandash.plugins.ai.dispatch.RuleCoverage
import io.karandash.plugins.ai.dispatch.SkipEvalInput
import io.karandash.plugins.ai.dispatch.SkipResult
import io.karandash.plugins.ai.dispatch.SkippedFiles
import io.karandash.plugins.ai.init.AiRuleInitializer
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.sdk.module.AbstractRuleModule
import io.karandash.utils.finding.FindingFingerprint
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * Main entry point for the AI analysis plugin.
 *
 * Implements `RuleModule` to contribute AI-powered validation rules. Delegates
 * initialization to [AiRuleInitializer], skip evaluation to [AiSkipEvaluator],
 * MR preprocessing to [AiMrPreprocessor], prompt assembly to [AiPromptAssembler],
 * dispatch to [AiRuleDispatcher], and post-processing to [AiFeedbackCostFilter].
 *
 * @param registry Module registry for resolving AI providers by name.
 */
class AiRuleModule(
    private val registry: ModuleRegistry,
) : AbstractRuleModule(),
    FindingsAwareRuleModule,
    AiStatePersistenceAware,
    StatePersistenceAware,
    SkipReasonProvider,
    FixDismissalAware {
    override val id: String = "ai-analysis"
    override val name: String = "AI Analysis"
    override val version: String = "1.7.0"

    /**
     * Aggregates [io.karandash.api.ai.AiAnalyzerModule.supportedEnrichment] across every
     * registered analyzer (e.g. `design-doc-drift` -> jira/confluence). Makes [io.karandash.cli.pipeline.ModuleInitializer.deliverEnrichmentToRuleModules]
     * treat this module as enrichment-aware so it gets re-initialized with real data -- without
     * this, no analyzer's declared interest (nor any `rule-enrichment:` binding targeting an
     * AI rule) ever has an effect, regardless of what [io.karandash.plugins.ai.dispatch.AiPromptAssembler]
     * does with the data once received.
     *
     * `by lazy`: evaluated on first access (at re-init-candidate scan time, well after all
     * analyzer modules are registered), not at construction, when the registry may still be
     * incomplete.
     */
    override val supportedEnrichment: List<String> by lazy {
        registry.aiAnalyzers().flatMap { it.supportedEnrichment }.distinct()
    }

    override val usesExtensionComputedConfig: Boolean = true

    /** `ai.executionTimeout`: the engine bounds each AI rule by the larger of it and `timeouts.ruleExecution`. */
    override val executionTimeout: Duration?
        get() = initState.value
            ?.pluginCfg
            ?.executionTimeout
            ?.toKotlinDuration()

    /**
     * Immutable snapshot of all configuration resolved during [initialize].
     * Stored atomically so that concurrent reads from [validate] always see a
     * fully-constructed value or `null` (not initialized yet / already shut down).
     */
    private val injectedPersistence = atomic<AiStatePersistence?>(null)

    override fun configureStatePersistence(persistence: AiStatePersistence?) {
        injectedPersistence.value = persistence
    }

    override fun configureStateStore(store: ExtensionStateStore?) = configureStatePersistence(store?.let { AiStatePersistence(it) })

    override fun onFindingFixed(finding: Finding) {
        val state = initState.value ?: return
        if (!state.pluginCfg.feedbackEnabled) return
        val filePath = (finding.location as? io.karandash.models.finding.Location.FileRef)?.path
        val semFp = FindingFingerprint.generate(finding.rule, finding.location, finding.message)
        state.feedbackStore.dismiss(
            rule = finding.rule,
            filePath = filePath,
            messageHash = finding.message.hashCode(),
            messagePrefix = finding.message.take(DismissedEntry.MESSAGE_PREFIX_MAX_LENGTH),
            semanticFingerprint = semFp,
        )
        state.runtimeContext.logger.debug(
            "AI plugin: dismissed auto-fixed finding [${finding.rule}]: ${finding.message.take(FIXED_LOG_LIMIT)}"
        )
    }

    private val initState = atomic<AiRuleInitState?>(null)

    private val aiState = atomic(AiRuleState())

    /** Duo instructions read from the MR base by [AiPipelineExtension.onBaseFiles]; survive a re-[initialize]. */
    private val baseInstructions = atomic(emptyList<ReviewInstruction>())
    private val ruleGate = io.karandash.plugins.ai.core
        .RuleGate()

    /** Source MR and its redacted copy; reused only for the same MR content (watch mode re-fetches). */
    private val redactedMrRef = atomic<Pair<MergeRequest, MergeRequest>?>(null)
    private val enrichmentScope = AiRuleEnrichmentScope(registry)
    private val coreValidator = AiRuleCoreValidator(
        registry,
        enrichmentScope,
        AiRuleCoreValidator.StateAccess(::readInitState, ::updateInitState, ::readRunState, ::updateRunState),
    )

    // atomicfu fields cannot be captured by lambdas in property initializers; expose them through methods.
    private fun readInitState(): AiRuleInitState? = initState.value

    private fun updateInitState(f: (AiRuleInitState) -> AiRuleInitState) = initState.update { it?.let(f) }

    private fun readRunState(): AiRuleState = aiState.value

    private fun updateRunState(f: (AiRuleState) -> AiRuleState) = aiState.update { f(it) }

    override suspend fun initialize(context: PluginContext): Boolean = initialize(context, emptyMap())

    override suspend fun initialize(
        context: PluginContext,
        ruleEnrichmentBindingPrefixes: Map<String, List<String>>,
    ): Boolean {
        val initializer = AiRuleInitializer(registry, injectedPersistence.value)
        val result = try {
            initializer.initialize(context, ruleEnrichmentBindingPrefixes)
        } catch (e: IllegalArgumentException) {
            // A wrong-typed ai config field (SerializationException) is bad config: report it with false, never throw.
            context.warn("AI module skipped: invalid ai config: ${e.message}")
            null
        } catch (e: ConfigParseException) {
            context.warn("AI module skipped: invalid ai config: ${e.message}")
            null
        } ?: return false

        initState.value?.let { previous ->
            previous.provider?.close()
            previous.namedProviders.values.forEach { it.close() }
            previous.aiService?.shutdown()
            previous.promptResolver.close()
        }

        val skipEvaluator = AiSkipEvaluator(
            logger = result.runtimeContext.logger,
            ruleGate = ruleGate,
            canaryTracker = CanaryTracker(result.statePersistence),
        )
        val mrPreprocessor = AiMrPreprocessor(result.runtimeContext.logger)
        val dispatcher = AiRuleDispatcher(registry, result.runtimeContext.logger)
        val postProcessor =
            AiFeedbackCostFilter(
                result.runtimeContext.logger,
                AiCostEstimator.fromConfig(result.pluginCfg.pricing, result.pluginCfg.promptCacheTtl)
            )
        val promptAssembler = AiPromptAssembler(registry, result.runtimeContext.logger)

        initState.update {
            AiRuleInitState(
                runtimeContext = result.runtimeContext,
                provider = result.provider,
                tokenBudget = result.tokenBudget,
                enabledRules = result.enabledRules,
                model = result.model,
                failOnError = result.failOnError,
                pluginCfg = withBaseInstructions(result.pluginCfg),
                aiRuleConfig = result.aiRuleConfig,
                concurrencySemaphore = result.concurrencySemaphore,
                perRuleModels = result.perRuleModels,
                perRuleTemperatures = result.perRuleTemperatures,
                normalizedRuleProviders = result.normalizedRuleProviders,
                strictness = result.strictness,
                customRules = result.customRules,
                namedProviders = result.namedProviders,
                perRuleBudgets = result.perRuleBudgets,
                aiService = result.aiService,
                promptSources = result.promptSources,
                promptResolver = result.promptResolver,
                statePersistence = result.statePersistence,
                cachingProvider = result.cachingProvider,
                currentMrKey = null,
                pluginStartMark = result.pluginStartMark,
                snapshotManager = null,
                feedbackStore = result.feedbackStore,
                skipEvaluator = skipEvaluator,
                mrPreprocessor = mrPreprocessor,
                dispatcher = dispatcher,
                postProcessor = postProcessor,
                promptAssembler = promptAssembler,
                enrichment = result.enrichment,
                ruleEnrichmentBindingPrefixes = result.ruleEnrichmentBindingPrefixes,
            )
        }
        return true
    }

    /**
     * Returns the total number of AI tokens consumed during this pipeline run.
     *
     * Returns 0 when no AI calls were made (budget exhausted, skipped, or not initialized).
     * Used by [AiPipelineExtension] to detect budget-exhaustion conditions for cache safety.
     */
    fun totalTokensConsumed(): Int {
        val state = initState.value ?: return 0
        val budgetConsumed = state.tokenBudget?.totalConsumed() ?: 0
        return if (budgetConsumed > 0) {
            budgetConsumed
        } else {
            aiState.value.ruleTokenCounts.values
                .sum()
        }
    }

    override fun tokensConsumed(): Int = totalTokensConsumed()

    /**
     * Adds [extra] (the Duo instructions read from the MR base) to `ai.review.instructions`, once each. Kept in
     * [baseInstructions] too, so an [initialize] before or after this call (the enrichment re-init) keeps them.
     */
    internal fun addReviewInstructions(extra: List<ReviewInstruction>) {
        baseInstructions.update { (it + extra).distinct() }
        updateInitState { s -> s.copy(pluginCfg = withBaseInstructions(s.pluginCfg)) }
    }

    private fun withBaseInstructions(cfg: AiPluginConfig): AiPluginConfig {
        val extra = baseInstructions.value
        if (extra.isEmpty()) return cfg
        return cfg.copy(review = cfg.review.copy(instructions = (cfg.review.instructions + extra).distinct()))
    }

    /** The run's token budget and `ai.concurrency` permits, for the post-validation verifier call; null before [initialize]. */
    internal fun runLimits(): Pair<AiTokenBudget?, Semaphore>? = initState.value?.let { it.tokenBudget to it.concurrencySemaphore }

    /** charges an auxiliary [stage] call's [tokens] to the analyzers behind [findings]; no-op before [initialize]. */
    internal fun chargeAuxiliary(
        stage: String,
        findings: List<Finding>,
        tokens: Int,
    ) {
        val enabled = initState.value?.enabledRules ?: return
        aiState.update { s ->
            val analyzers = AiAuxiliaryLedger.triggeredBy(findings, enabled, s.ruleTokenCounts.keys)
            s.copy(auxiliaryTokens = AiAuxiliaryLedger.charge(s.auxiliaryTokens, stage, tokens, analyzers))
        }
    }

    /** why auxiliary calls stop now (the run's `max-tokens-per-run` safety cap), or null; null before [initialize]. */
    internal fun auxiliaryCapReason(sourceBranch: String): String? =
        initState.value?.let { AiAuxiliaryLedger.capReason(it, aiState.value, sourceBranch) }

    /** Files the preprocessing left out of at least one rule's diff this run; empty before [initialize]. */
    internal fun skippedFiles(): SkippedFiles =
        initState.value
            ?.mrPreprocessor
            ?.skippedFiles
            ?.snapshot() ?: SkippedFiles()

    /**
     * Returns the end-of-run cost summary lines exactly once per run; later calls (and calls before
     * [initialize]) return an empty list. Read by [AiPipelineExtension.costSummary] for the CLI summary.
     */
    fun consumeCostSummaryLines(): List<String> {
        val state = initState.value ?: return emptyList()
        val snapshot = aiState.getAndUpdate { it.copy(costSummaryLogged = true) }
        if (snapshot.costSummaryLogged) return emptyList()
        AiRuleCostLogger
            .alertMessage(snapshot.totalEstimatedCostUsd, state.pluginCfg.costBudget?.costEstimate)
            ?.let { state.runtimeContext.warn(it) }
        return AiRuleCostLogger.summaryLines(state, snapshot)
    }

    /** Logs the cost summary via the plugin context unless it was already consumed this run. */
    override fun logCostSummary() {
        val state = initState.value ?: return
        consumeCostSummaryLines().forEach { state.runtimeContext.log(it) }
    }

    override suspend fun shutdown() {
        baseInstructions.value = emptyList()
        val state = initState.value ?: return
        // Must run before initState is cleared, otherwise the shutdown summary is silently dropped.
        logCostSummary()
        initState.update { null }
        aiState.update { AiRuleState() }
        AiMrStatePersistence.save(state)
        state.skipEvaluator.canaryTracker.persist()
        redactedMrRef.value = null
        state.provider?.close()
        state.namedProviders.values.forEach { it.close() }
        state.aiService?.shutdown()
        state.promptResolver.close()
        state.feedbackStore.clear()
    }

    override suspend fun contributedRules(): Set<String> {
        val state = initState.value ?: return emptySet()
        return state.enabledRules + state.customRules.map { it.id }
    }

    /**
     * AI rules of this run that did not complete: no analyzer result and no skip a re-run would repeat (provider
     * error, rate limit, budget, dry run, or not run at all, e.g. after a bail-on-fail). Empty before [initialize].
     * Limitation: an analyzer that turns a failed call into "no findings" itself still counts as completed.
     */
    internal fun incompleteRules(): Set<String> {
        val state = initState.value ?: return emptySet()
        val run = aiState.value
        val final = run.ruleSkipReasons.filterValues { it.category !in RETRYABLE_SKIPS }.keys
        return state.enabledRules + state.customRules.map { it.id } - run.ruleModels.keys - final
    }

    override suspend fun ruleExplanation(ruleId: String): String? =
        registry.findAiAnalyzer(ruleId)?.explanation?.ifEmpty { null }
            ?: initState.value
                ?.customRules
                ?.find { it.id == ruleId }
                ?.name

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding> {
        val state = initState.value ?: return AiRuleErrorHandlers.errorFinding(ruleId, AiRuleErrorHandlers.ERR_NOT_INITIALIZED)
        val aiProvider = resolveAiProvider(ruleId, state)
            ?: return AiRuleErrorHandlers.errorFinding(ruleId, AiRuleErrorHandlers.ERR_NOT_INITIALIZED)
        val budget = state.tokenBudget ?: return AiRuleErrorHandlers.errorFinding(ruleId, "Token budget not initialized")

        val skipInput = buildSkipEvalInput(state)
        val skipResult = state.skipEvaluator.evaluateSkipConditions(ruleId, mr, skipInput, budget)
        if (skipResult is SkipResult.Skip) {
            aiState.update { s ->
                val coverage = skipResult.coverage?.let { s.ruleCoverage + (ruleId to it) } ?: s.ruleCoverage
                s.withSkip(ruleId, skipResult.reason).copy(ruleCoverage = coverage)
            }
            return skipResult.findings
        }

        if (budget.totalConsumed() == 0) {
            aiState.update { it.copy(accumulatedFindings = emptyList()) }
        }

        val effectiveBudget = state.perRuleBudgets[ruleId] ?: budget
        // A spent budget is skipped here, before the prompt assembly's enrichment I/O; the dispatcher gate covers the rest.
        // The primary analyzer on its own share is bounded by that share alone (AiRuleCoreValidator.Request.primary).
        val runGates = !AiRuleCoreValidator.isPrimary(ruleId, budget, effectiveBudget)
        if ((runGates && budget.isExhausted()) || effectiveBudget.isExhausted()) return skipBudgetExhausted(ruleId, mr, state)

        val redactedMr = prepareRedactedMr(ruleId, mr)

        val request = AiRuleCoreValidator.Request(
            ruleId,
            redactedMr,
            budget,
            effectiveBudget,
            aiProvider,
            RuleCallOverride.fromConfig(configElement),
        )
        return try {
            // the profile totalTimeout also stops an analyzer that is already running
            withTimeoutOrNull(profileRemaining(state) ?: Duration.INFINITE) { coreValidator.execute(request) }
                ?: skipProfileTimeout(ruleId, state)
        } catch (e: AiProviderException) {
            aiState.update { it.withSkip(ruleId, AiRuleErrorHandlers.providerErrorReason(e), SkipCategory.AI_PROVIDER_ERROR) }
            AiRuleErrorHandlers.providerError(ruleId, e, state)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // boundary: AI rule module validate()
            e.rethrowCancellation()
            aiState.update { it.withSkip(ruleId, AiRuleErrorHandlers.providerErrorReason(e), SkipCategory.AI_PROVIDER_ERROR) }
            AiRuleErrorHandlers.unexpectedError(ruleId, e, state)
        }
    }

    private fun skipProfileTimeout(
        ruleId: String,
        state: AiRuleInitState,
    ): List<Finding> {
        state.runtimeContext.warn("AI plugin: stopped $ruleId: the execution profile totalTimeout passed while it ran")
        aiState.update { it.withSkip(ruleId, AiSkipEvaluator.PROFILE_TIMEOUT_REASON) }
        return emptyList()
    }

    private fun skipBudgetExhausted(
        ruleId: String,
        mr: MergeRequest,
        state: AiRuleInitState,
    ): List<Finding> {
        state.runtimeContext.warn("[ai] rule=$ruleId: token budget exhausted, skipped before prompt assembly")
        val coverage = RuleCoverage.of(mr.changes, emptyList(), chunks = 1, chunksSkipped = 1, truncateAbove = Int.MAX_VALUE)
        aiState.update { s ->
            s.withSkip(ruleId, RuleCoverage.BUDGET_EXHAUSTED).copy(ruleCoverage = s.ruleCoverage + (ruleId to coverage))
        }
        return emptyList()
    }

    override suspend fun validateWithFindings(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
        context: ValidationContext,
    ): List<Finding> {
        val state = initState.value ?: return emptyList()
        val strategyConfig = state.pluginCfg.ruleStrategies[ruleId] ?: AiRuleStrategyConfig()

        when (strategyConfig.strategy) {
            AiRuleStrategy.FALLBACK -> {
                if (shouldSkipFallback(strategyConfig, context)) {
                    val coveredBy = strategyConfig.covers.joinToString(", ")
                    state.runtimeContext.log("AI plugin: Skipping $ruleId (fallback) — covered by $coveredBy")
                    aiState.update { it.withSkip(ruleId, "fallback — covered by $coveredBy") }
                    return emptyList()
                }
            }

            AiRuleStrategy.SUPPLEMENT -> {
                val relevantFindings = strategyConfig.covers.flatMap { context.findingsFor(it) }
                if (relevantFindings.isNotEmpty()) {
                    aiState.update { s ->
                        val existingRules = s.accumulatedFindings.map { it.rule }.toSet()
                        val newFindings = relevantFindings.filter { it.rule !in existingRules }
                        s.copy(accumulatedFindings = s.accumulatedFindings + newFindings)
                    }
                }
            }

            AiRuleStrategy.ALWAYS -> {
                Unit
            }
        }
        if (ruleId == AiRuleIds.FIX_SUGGESTIONS) {
            val aiRules = state.enabledRules + state.customRules.map { it.id }
            aiState.update { s -> s.copy(deterministicFindings = context.priorFindings.filter { it.rule !in aiRules }) }
        }

        return validate(ruleId, mr, configElement)
    }

    /** Each AI rule's coverage this run, for the review-unit summary. */
    internal fun ruleCoverages(): Collection<RuleCoverage> = aiState.value.ruleCoverage.values

    /** [LargeMrSummary] of this run's coverage, judged with the effective `ai.size-policy.exclude.patterns`. */
    internal fun largeMrSummary(
        changes: List<FileChange>,
        config: AiLargeMrConfig,
        testGap: TestGapConfig,
    ): List<Finding> {
        val patterns = initState.value
            ?.aiRuleConfig
            ?.excludePolicy
            ?.patterns ?: AiExcludePolicy.DEFAULT_PATTERNS
        return LargeMrSummary.findings(changes, ruleCoverages(), config, patterns, testGap)
    }

    override fun reviewProvenance(): ReviewProvenance? = aiState.value.let { it.provenance(it.aiStatusLine(configured = isConfigured())) }

    /** Whether this run had AI rules switched on; the status line is printed only then. */
    private fun isConfigured(): Boolean = initState.value?.let { it.enabledRules.isNotEmpty() || it.customRules.isNotEmpty() } == true

    /** Provider health of this run, for the pipeline extension's log line, health finding and save reason. */
    internal fun health(): AiHealth = aiState.value.health()

    /** Records that the pipeline extension served [count] AI findings from the incremental cache. */
    internal fun recordCacheServed(count: Int) = aiState.update { it.copy(cachedFindings = count) }

    /** AR1/N1: AI rules a policy switched off this run; their cached findings must not be posted. */
    internal fun policyDisabledRules(): Set<String> =
        aiState.value.engineSkips.values
            .filter { it.category == SkipCategory.DISABLED_BY_POLICY }
            .map { it.ruleId }
            .toSet()

    /** Records the AI pipeline extension's post-validation filter stages and review funnel for [reviewProvenance]. */
    internal fun recordPostFilters(
        filters: List<FilterProvenance>,
        funnel: Map<String, Int> = emptyMap(),
    ) = aiState.update { it.copy(postFilters = filters, reviewFunnel = funnel) }

    /** Records the finding hash chain for [reviewProvenance]. */
    internal fun recordChain(chain: FindingHashChain) = aiState.update { it.copy(chain = chain) }

    override fun skippedRuleReasons(): Map<String, SkippedRuleInfo> {
        val snapshot = aiState.value
        // Rules skip in completion order; sort so the report does not depend on scheduling.
        return snapshot.ruleSkipReasons.entries
            .sortedBy { it.key }
            .associate { it.key to it.value }
    }

    /** Keeps the engine's skips of AI rules, so the status line and cost summary can name policy and bail skips. */
    override fun onEngineSkips(skips: List<SkippedRuleInfo>) {
        val own = skips.filter { registry.findRuleHandler(it.ruleId) === this }.associateBy { it.ruleId }
        aiState.update { it.copy(engineSkips = own) }
    }

    // -- private orchestration helpers --

    private fun resolveAiProvider(
        ruleId: String,
        state: AiRuleInitState
    ): AiProvider? {
        val ruleProviderName = state.normalizedRuleProviders[ruleId]
        val namedProvider = ruleProviderName?.let { state.namedProviders[it] }
        val aiProvider = namedProvider ?: state.provider
        if (aiProvider != null) {
            state.runtimeContext.logger.debug(
                "AI plugin [$ruleId]: using provider ${aiProvider.providerId} " +
                    "(${aiProvider::class.simpleName}), ruleProvider=$ruleProviderName, named=${namedProvider != null}",
            )
        }
        return aiProvider
    }

    private fun buildSkipEvalInput(state: AiRuleInitState): SkipEvalInput =
        SkipEvalInput(
            gates = state.pluginCfg.gates,
            costBudget = state.pluginCfg.costBudget,
            tracks = state.pluginCfg.tracks,
            canary = state.pluginCfg.canary,
            skipPolicy = state.aiRuleConfig.skipPolicy,
            excludePatterns = state.aiRuleConfig.excludePolicy.patterns,
            profileTimedOut = profileRemaining(state)?.isPositive() == false,
            spentUsd = aiState.value.totalEstimatedCostUsd,
        )

    /** Time left of the execution profile `totalTimeout` since the plugin started; null without one. */
    private fun profileRemaining(state: AiRuleInitState): Duration? {
        val limit = ExecutionProfileResolver
            .resolve(state.pluginCfg.executionProfile, state.pluginCfg.executionProfiles)
            ?.totalTimeout
            ?.toKotlinDuration()
            ?.takeIf { it.isPositive() } ?: return null
        return state.pluginStartMark?.let { limit - it.elapsedNow() }
    }

    private fun prepareRedactedMr(
        ruleId: String,
        mr: MergeRequest
    ): MergeRequest {
        PgoProfile.recordPath("rule:$ruleId")
        return redactedMrRef.value?.takeIf { it.first == mr }?.second ?: SecretRedactor.redact(mr).also { redactedMrRef.value = mr to it }
    }

    private fun shouldSkipFallback(
        strategyConfig: AiRuleStrategyConfig,
        context: ValidationContext
    ): Boolean {
        if (strategyConfig.covers.isEmpty()) return false
        val minSev = when (strategyConfig.minSeverity.lowercase()) {
            "error" -> Severity.ERROR
            "warning" -> Severity.WARNING
            else -> Severity.INFO
        }
        return strategyConfig.covers.any { coveredRuleId ->
            context.findingsFor(coveredRuleId, minSev).isNotEmpty()
        }
    }

    /** Test seam for per-rule enrichment isolation; see [AiRuleEnrichmentScope.scoped]. */
    internal fun ruleScopedEnrichment(
        ruleId: String,
        moduleEnrichment: EnrichmentContext,
        ruleEnrichmentBindingPrefixes: Map<String, List<String>>,
    ): EnrichmentContext = enrichmentScope.scoped(ruleId, moduleEnrichment, ruleEnrichmentBindingPrefixes)

    companion object {
        private const val FIXED_LOG_LIMIT = 80

        /** Skips caused by the run, not the MR (provider, rate limit, budget, dry run): a re-run may complete the rule. */
        private val RETRYABLE_SKIPS = setOf(
            SkipCategory.AI_PROVIDER_ERROR,
            SkipCategory.AI_RATE_LIMITED,
            SkipCategory.AI_BUDGET_EXHAUSTED,
            SkipCategory.AI_DRY_RUN,
        )
    }
}

/**
 * Builds the `config` [JsonElement] passed to every [io.karandash.api.ai.AiContextEnricherModule].
 *
 * That interface's `config` parameter is overloaded by two different enricher shapes:
 * - `FullFileContextEnricher`/`ConventionContextEnricher` read their own feature flags
 *   from the `ai:` plugin config (e.g. `contextQuality.fullFileContext`, `convention-context.files`).
 * - `TicketContentEnricher`/`CiContextEnricher`/`SymbolContextEnricher` read live enrichment
 *   provider data keyed by enrichment ID (e.g. `"jira:tickets"`, `"ci:failed-jobs"`).
 *
 * Merging both into one object is safe: enrichment IDs always contain a `:` separating
 * provider from data type (per [EnrichmentContext] convention), while `ai:` config keys
 * never do -- the two key sets cannot collide.
 */
internal fun mergedEnricherConfigJson(
    pluginConfig: JsonElement,
    enrichment: EnrichmentContext
): JsonElement {
    val pluginConfigEntries = (pluginConfig as? JsonObject)?.toMap().orEmpty()
    val enrichmentEntries = enrichment.availableIds.associateWith { id ->
        checkNotNull(enrichment.get(id)) { "enrichment id '$id' vanished" }
    }
    val merged = pluginConfigEntries + enrichmentEntries
    return if (merged.isEmpty()) JsonNull else JsonObject(merged)
}
