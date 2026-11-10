@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.Version
import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiService
import io.karandash.api.finding.FilterProvenance
import io.karandash.api.module.ExtensionStateStore
import io.karandash.api.module.ModuleScope
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.StatePersistenceAware
import io.karandash.api.platform.FetchOptions
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.run.dumpJson
import io.karandash.api.support.rethrowCancellation
import io.karandash.config.keys.ModuleIds
import io.karandash.crypto.sha256First64Bits
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiEnvVars
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.AiStatePersistenceAware
import io.karandash.plugins.ai.core.AuthorHistory
import io.karandash.plugins.ai.core.DiffMode
import io.karandash.plugins.ai.core.HunkExpander
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.MrHistory
import io.karandash.plugins.ai.core.ProcessRiskMetrics
import io.karandash.plugins.ai.core.TemporalConfig
import io.karandash.plugins.ai.core.TriageRisk
import io.karandash.plugins.ai.core.config.AiPipelineConfig
import io.karandash.plugins.ai.core.config.DefectPredictorConfig
import io.karandash.plugins.ai.core.config.ExecutionProfileResolver
import io.karandash.plugins.ai.core.context.ConventionContextEnricher
import io.karandash.plugins.ai.core.context.FullFileContextEnricher
import io.karandash.plugins.ai.core.context.SymbolDefinitionsEnricher
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.plugins.ai.core.incremental.IncrementalContext
import io.karandash.plugins.ai.core.metrics.CalibrationEngine
import io.karandash.plugins.ai.core.metrics.CognitiveLoadEstimator
import io.karandash.plugins.ai.core.pipeline.AiMetadataTransformer
import io.karandash.plugins.ai.core.reporters.DigestAudience
import io.karandash.plugins.ai.core.reporters.SmartDigestGenerator
import io.karandash.plugins.ai.core.research.AdaptiveDefectModel
import io.karandash.plugins.ai.core.research.DefectModelState
import io.karandash.plugins.ai.core.research.DefectPrediction
import io.karandash.plugins.ai.core.research.DefectRiskLevel
import io.karandash.plugins.ai.core.research.JitDefectPredictor
import io.karandash.plugins.ai.dispatch.SkippedFiles
import io.karandash.plugins.ai.hooks.AiHookExecutor
import io.karandash.plugins.ai.init.AiBaseReviewFiles
import io.karandash.plugins.ai.init.isFeatureEnabled
import io.karandash.plugins.ai.pipeline.AiArchitectureChecks
import io.karandash.plugins.ai.pipeline.AiArchitectureOptions
import io.karandash.plugins.ai.pipeline.AiConfigFeatureApplicator
import io.karandash.plugins.ai.pipeline.AiFindingPostProcessor
import io.karandash.plugins.ai.pipeline.AiMetricsEnricher
import io.karandash.plugins.ai.pipeline.AiMetricsOptions
import io.karandash.plugins.ai.pipeline.AiPipelineHooks
import io.karandash.plugins.ai.pipeline.AiReportGenerator
import io.karandash.plugins.ai.pipeline.AiReporterSection
import io.karandash.plugins.ai.pipeline.BudgetedAiService
import io.karandash.plugins.ai.pipeline.CheckRunAnnotationReport
import io.karandash.plugins.ai.statistics.StatisticsCollector
import io.karandash.plugins.ai.statistics.StatisticsStore
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.state.snapshot.SnapshotManager
import io.karandash.utils.finding.FindingFactory
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Clock

/**
 * Pipeline extension that encapsulates all AI-specific post-validation logic.
 *
 * Handles:
 * - AI hook execution (post-fetch, post-validation, per-finding, pre-report)
 * - AI finding filtering (deduplication, confidence thresholds, caps)
 * - AI finding enrichment (LLM-powered message improvement)
 * - AI metadata transformation (bake metadata into standard Finding fields)
 * - AI cost summary logging
 * - Validation statistics collection
 * - AI-specific incremental validation state
 *
 * Delegates specialized logic to:
 * - [AiConfigFeatureApplicator] for config-driven feature application
 * - [AiFindingPostProcessor] for filtering, enrichment, and correlation
 * - [AiMetricsEnricher] for metrics-based finding enrichment
 * - [AiReportGenerator] for report generation
 *
 * [ModuleScope.PER_RUN]: every run gets its own handle ([newHandle]), so the per-run fields set in
 * [onConfigured] and [onPostFetch] never leak between overlapping runs.
 *
 * @lifecycle per-run -- one handle per validation run
 */
class AiPipelineExtension(
    private val registry: ModuleRegistry,
) : PipelineExtension,
    AiStatePersistenceAware,
    StatePersistenceAware {
    override val id: String = ID
    override val name: String = "AI Pipeline Extension"
    override val version: String = "1.0.0"
    override val type: ModuleType = ModuleType.PIPELINE_EXTENSION
    override val defaultPriority: Int = 50
    override val scope: ModuleScope get() = ModuleScope.PER_RUN

    override suspend fun newHandle(context: PluginContext): Module = AiPipelineExtension(registry)

    private val loggerRef = atomic<Logger>(NoOpLogger)
    private val logger: Logger get() = loggerRef.value
    private val aiServiceRef = atomic<AiService?>(null)
    private val aiService: AiService? get() = aiServiceRef.value

    /** auxiliary stages skipped this run by the `max-tokens-per-run` safety cap, with the last reason. */
    private val auxiliarySkips = atomic<Pair<List<String>, String>?>(null)
    private val statsDirRef = atomic<String?>(null)
    private val platformIdRef = atomic<String?>(null)
    private val pluginConfigRef = atomic(AiPluginConfig())
    private val baseReviewFiles = AiBaseReviewFiles()
    private val pipelineConfig: AiPipelineConfig get() = pluginConfigRef.value.pipeline
    private val injectedPersistence = atomic<AiStatePersistence?>(null)
    private val defectPredictorConfig = atomic(DefectPredictorConfig())

    /** statistics, defect prediction and its drift check run only for a config with an enabled `ai:` block. */
    private val aiConfigured = atomic(false)

    override fun configureStatePersistence(persistence: AiStatePersistence?) {
        injectedPersistence.value = persistence
    }

    override fun configureStateStore(store: ExtensionStateStore?) = configureStatePersistence(store?.let { AiStatePersistence(it) })

    /**
     * Holds per-validation-run state captured across pipeline stages.
     *
     * Stages run sequentially per invocation ([onPostFetch] -> [afterValidation] ->
     * [beforeReporting] -> [onPipelineComplete]), but the extension may be reused
     * across multiple sequential runs. [AtomicRef] gives visibility and
     * safe replacement between runs without requiring mutable fields for each datum.
     */
    private val runState = atomic<ValidationRunState?>(null)

    /** `KARANDASH_REVIEW_MODE`, which overrides `ai.reviewMode` as in the AI rule's initializer. */
    private val envReviewMode = atomic<DiffMode?>(null)

    override suspend fun initialize(context: PluginContext): Boolean {
        loggerRef.value = context.logger
        envReviewMode.value =
            context.env(AiEnvVars.KARANDASH_REVIEW_MODE)?.let(DiffMode::fromString)
        return true
    }

    override suspend fun onConfigured(env: PipelineEnvironment) {
        val aiService = env.aiService
        val rawConfig = env.rawConfig
        // A run that reads no base files must not see the previous run's convention files.
        conventionEnrichers().forEach { it.useBaseFiles(emptyMap()) }
        baseReviewFiles.reset()
        aiServiceRef.value = aiService
        auxiliarySkips.value = null
        configuredRawConfig.value = rawConfig
        // set before the ai block is parsed, so an unparsable block cannot leave statistics on the default dir.
        statsDirRef.value = env.statsDir
        aiConfigured.value = ((rawConfig as? JsonObject)?.get("ai") as? JsonObject)
            ?.let { (it["enabled"] as? JsonPrimitive)?.booleanOrNull != false } == true
        // rawConfig is the whole config; resolveAiConfig reads its `ai:` block (or a bare ai block)
        val aiConfig = resolveAiConfig(rawConfig) ?: AiPluginConfig()
        defectPredictorConfig.value = aiConfig.defectPredictor
        pluginConfigRef.value = aiConfig
        platformIdRef.value = env.platformId
    }

    override suspend fun onPostFetch(
        mr: MergeRequest,
        config: JsonElement?,
    ) {
        incrementalCache.reset()
        if (aiConfigured.value) injectedPersistence.value?.prefetch(SnapshotManager.mrKey(mr))
        val riskConfig = pluginConfigRef.value.processRisk?.takeIf { it.enabled }
        val history = if (aiConfigured.value || riskConfig != null) AuthorHistory.load(mr, logger) else MrHistory()
        val processRisk = TriageRisk.of(mr, riskConfig, history)
        runState.value = ValidationRunState(mrId = mr.id, mr = mr, processRisk = processRisk)
        if (riskConfig != null) {
            val high = pipelineConfig.thresholds.highRisk.fraction
            logger.info(
                ProcessRiskAssessment.headline(processRisk, high) + "\n" + ProcessRiskAssessment.breakdown(processRisk, riskConfig.factors),
            )
        } else if (aiConfigured.value && processRisk.composite > pipelineConfig.thresholds.highRisk.fraction) {
            logger.info("Process risk: HIGH (${processRisk.composite})")
        }
        val defect = if (aiConfigured.value) predictDefect(mr) else null
        if (defect?.riskLevel == DefectRiskLevel.HIGH) {
            logger.info("JIT defect prediction: HIGH (p=${defect.probability.formatDecimals(2)})")
        }
        hooks(mr, emptyList())?.run("post-fetch", mr, emptyList())
    }

    override suspend fun afterValidation(
        findings: List<Finding>,
        mr: MergeRequest,
        config: JsonElement?,
    ): List<Finding> {
        if (incrementalCache.context?.isFullyCached == true) {
            return findings // already post-processed from prior run
        }
        val postProcessor = AiFindingPostProcessor(logger)
        val configApplicator = AiConfigFeatureApplicator(logger, persistence = injectedPersistence.value)
        val metricsEnricher = AiMetricsEnricher(pipelineConfig, logger)

        var result = findings
        val removed = mutableListOf<FilterProvenance>()

        result = removed.track("execution-profile", result) { ExecutionProfileResolver.filterFindings(pluginConfigRef.value, it, config) }
        result = hooks(mr, result)?.postValidation(mr, result) ?: result
        result = configApplicator.apply(result, mr, pluginConfigRef.value)
        dumpJson("ai/stages/config-features.json") { result }
        result = removed.track("learnings", result) { baseReviewFiles.apply(it) }
        val architecture = AiArchitectureOptions.from(config) { logger.warn(it) }
        val architectureChecks = AiArchitectureChecks(logger)
        result = architectureChecks.apply(result, mr, architecture, configuredRawConfig.value)
        result =
            result + architectureChecks.crossMrDependencies(mr, architecture, statsDirRef.value ?: pluginConfigRef.value.statistics.dir)
        result = result + architectureChecks.mutationTesting(architecture)
        dumpJson("ai/stages/architecture.json") { result }
        val aiCfg = pluginConfigRef.value
        val constitution = aiCfg.constitution.let { it.enabled && it.principles.isNotEmpty() } == true
        result =
            removed.track("constitutional", result) {
                postProcessor.applyConstitutionalReview(it, config, budgeted("constitutional review", mr, it, constitution))
            }
        result = removed.track("grounding", result) { postProcessor.applyGrounding(it, mr, config) }
        result = removed.track("contradiction", result) { postProcessor.applyContradictionDetection(it, config) }
        result = removed.track("actionability", result) { postProcessor.applyActionability(it, config) }
        val reviewThreads = RunScopedServices.current()?.get(RunServiceKeys.REVIEW_THREADS)
        result = removed.track("duplicates", result) { postProcessor.applyDuplicates(it, config, reviewThreads) }
        val limits = aiRule()?.runLimits()
        // The filter first, so the verifier spends a call only on findings that survive confidence and caps
        result = removed.track("filter", result) { postProcessor.applyFilter(config, it, mr) }
        result = removed.track("verifier", result) { postProcessor.applyVerifier(it, mr, config, aiService, limits?.first, limits?.second) }
        result = postProcessor.applyEnricher(config, result, budgeted("enricher", mr, result, aiCfg.enricher.enabled))
        result = AiMetadataTransformer.transform(result)
        dumpJson("ai/stages/enricher.json") { result }
        result = removed.track("correlation", result) { postProcessor.applyCorrelation(it, config, mr) }
        val temporal = aiCfg.temporal
        if (pipelineConfig.temporalEscalation || temporal?.enabled == true) {
            result = postProcessor.applyTemporalEscalation(result, mr, injectedPersistence.value, temporal ?: TemporalConfig())
        }
        val enabledReports =
            resolveEnabledModuleIds(config, "reports") + AiReporterSection.from(configuredRawConfig.value).reportIds +
                architecture.reportIds
        val enabledMetrics = resolveEnabledModuleIds(config, "metrics")
        result = metricsEnricher.enrich(result, mr, enabledMetrics, AiMetricsOptions.from(config) { logger.warn(it) })
        dumpJson("ai/stages/metrics.json") { result }
        val funnel = ReviewFunnel.counts(findings, result, removed)
        aiRule()?.recordPostFilters(removed, funnel)
        ReviewFunnel.line(funnel)?.let { logger.info(it) }
        runState.update { prev ->
            (prev ?: ValidationRunState()).copy(
                findings = result,
                config = config,
                enabledReports = enabledReports,
                enabledMetrics = enabledMetrics,
            )
        }
        return result
    }

    override suspend fun beforeReporting(
        findings: List<Finding>,
        mr: MergeRequest,
    ): List<Finding> = report(findings, mr) + listOfNotNull(auxiliarySkipFinding(), aiRule()?.health()?.let { healthFinding(it, logger) })

    @Suppress("TooGenericExceptionCaught") // boundary: beforeReporting must not crash pipeline
    private suspend fun report(
        validated: List<Finding>,
        mr: MergeRequest,
    ): List<Finding> {
        val findings = hooks(mr, validated)?.run("pre-report", mr, validated) ?: validated
        ThreadAnswerStage.run(pluginConfigRef.value, configuredRawConfig.value, aiService, aiRule(), findings, mr, logger)

        val state = runState.value
        val aiRule = aiRule()
        val notSent = aiRule?.skippedFiles() ?: SkippedFiles()
        val incremental = incrementalCache.context?.let { IncrementalSummary.finding(it, notSent) }
        val largeMr = aiRule
            ?.largeMrSummary(
                (state?.mr ?: mr).changes,
                pluginConfigRef.value.largeMr,
                pluginConfigRef.value.testGap
            ).orEmpty()
        val summaries = listOfNotNull(processRiskFinding(state), incremental) + largeMr
        val enabledReports = state?.enabledReports ?: emptySet()
        val enabledMetrics = state?.enabledMetrics ?: emptySet()
        if (enabledReports.isEmpty() && enabledMetrics.isEmpty()) return findings + summaries

        val reportFindings = mutableListOf<Finding>()
        reportFindings.addAll(summaries)

        appendDigestSummary(reportFindings, findings, mr)
        appendCognitiveLoadSummary(reportFindings, enabledReports)
        appendProcessRiskSummary(reportFindings, state, enabledReports)

        val reportGenerator = AiReportGenerator(pipelineConfig, logger, statisticsStore().recentRecords(historyWindow()))
        val generatedReports = reportGenerator.generate(
            findings = findings,
            mr = state?.mr ?: mr,
            config = state?.config,
            mrId = state?.mrId ?: mr.id,
            aiConfig = pluginConfigRef.value,
            // reports with a registered report module come from the engine below, never from both paths
            enabledReports = enabledReports - registry.aiReports().map { it.reportId }.toSet(),
        )
        val reporterSection = AiReporterSection.from(configuredRawConfig.value)
        appendReportFindings(
            reportFindings,
            generatedReports + reporterSection.generate(findings, LanguagePromptOverlays.responseLanguageOf(configuredRawConfig.value))
        )

        val engineFindings = reportGenerator.runReportEngine(
            registry = registry,
            enabledReports = enabledReports,
            enabledMetrics = enabledMetrics,
            mr = state?.mr,
            config = state?.config,
            findings = findings,
        )
        reportFindings.addAll(engineFindings)
        reporterSection.writeOutputs(reportFindings, logger)
        AiArchitectureChecks(logger).persistProvenance(findings, state?.mr ?: mr, state?.config)?.let { aiRule?.recordChain(it) }

        return if (reportFindings.isNotEmpty()) findings + reportFindings else findings
    }

    /**
     * the run's AI service for an auxiliary [stage], charged to the analyzers behind [findings]. Null once the
     * `max-tokens-per-run` safety cap is reached; an [active] stage then logs and reports the skip.
     */
    private fun budgeted(
        stage: String,
        mr: MergeRequest,
        findings: List<Finding>,
        active: Boolean,
    ): AiService? {
        val service = aiService ?: return null
        val rule = aiRule() ?: return service
        // limit: the cap is checked once per stage, so one stage's calls can overshoot it; per-call gating needs
        // a "skipped" result in AiService.complete.
        val reason = rule.auxiliaryCapReason(mr.sourceBranch)
        if (reason == null) return BudgetedAiService(service, stage, logger) { rule.chargeAuxiliary(stage, findings, it) }
        if (active) {
            logger.info("AI $stage skipped: budget — $reason")
            auxiliarySkips.update { (it?.first.orEmpty() + stage).distinct() to reason }
        }
        return null
    }

    /** The configured `ai.hooks`, calling AI through [budgeted]; null with no hooks, no AI service or past the cap. */
    private fun hooks(
        mr: MergeRequest,
        findings: List<Finding>,
    ): AiPipelineHooks? {
        val raw = configuredRawConfig.value ?: return null
        val configured = aiService?.let { buildHookExecutor(raw, it) } != null
        val service = budgeted("hooks", mr, findings, configured) ?: return null
        return buildHookExecutor(raw, service)?.let { AiPipelineHooks(it, logger) }
    }

    /** the visible INFO finding naming the auxiliary stages the safety cap skipped; null when none was. */
    private fun auxiliarySkipFinding(): Finding? =
        auxiliarySkips.value?.let { (stages, reason) ->
            FindingFactory.info(rule = AUXILIARY_BUDGET_RULE, message = "AI ${stages.joinToString()} skipped: budget — $reason")
        }

    private fun aiRule(): AiRuleModule? = registry.ruleModules().filterIsInstance<AiRuleModule>().firstOrNull()

    override suspend fun onPipelineComplete() {
        if (aiConfigured.value) collectStatistics()
    }

    /** End-of-run AI cost summary (headline + per-rule lines), returned once per run for the CLI to print. */
    override fun costSummary(): String? =
        aiRule()
            ?.consumeCostSummaryLines()
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")

    override fun fetchOptions(config: JsonElement?): FetchOptions? =
        config?.let { FetchOptions(contextLines = AiPluginConfig.fromPluginConfig(it).contextLines) }

    /**
     * the configured rules the active execution profile excludes. Runs at config load, so it decodes only
     * the profile keys: a bad value elsewhere in the `ai:` block stays the AI module's to report at initialize.
     */
    override fun excludedRules(
        config: JsonElement?,
        ruleIds: Set<String>,
    ): Set<String> {
        val block = (config as? JsonObject)?.let { it["ai"] as? JsonObject ?: it } ?: return emptySet()
        val aiConfig = try {
            resolveAiConfig(JsonObject(block.filterKeys { it in EXECUTION_PROFILE_KEYS })) ?: return emptySet()
        } catch (e: IllegalArgumentException) {
            logger.warn("AI execution profile not applied to configured rules: ${e.message}")
            return emptySet()
        }
        val aiRules = registry.aiAnalyzers().map { it.analyzerId }.toSet() + ModuleIds.AI_ANALYSIS
        return ExecutionProfileResolver.excludedRules(aiConfig, ruleIds, aiRules)
    }

    /** True when an analyzer with `needsBlame` is enabled: `features.<key>` or a top-level `<key>` flag, else its default. */
    override fun needsBlame(config: JsonElement?): Boolean = config != null && anyAnalyzerEnabled(config) { it.needsBlame }

    /**
     * True when at least one analyzer is enabled (as for [needsBlame]) and the review is `reviewMode: full`
     * (config or the `KARANDASH_REVIEW_MODE` env override, [AiEnvVars.KARANDASH_REVIEW_MODE]) or has an active
     * `contextQuality.fullFileContext`, `contextQuality.hunk-expansion` or `contextQuality.symbolDefinitions`.
     */
    override fun needsHeadContent(config: JsonElement?): Boolean {
        if (config == null || !anyAnalyzerEnabled(config)) return false
        val reviewMode = envReviewMode.value ?: AiPluginConfig.fromPluginConfig(config).reviewMode
        return reviewMode == DiffMode.FULL || FullFileContextEnricher.isActive(config) || HunkExpander.isActive(config) ||
            SymbolDefinitionsEnricher.isActive(config)
    }

    /** The convention files ([ConventionContextEnricher.basePaths]) and the review files ([AiBaseReviewFiles.paths]). */
    override fun baseFiles(config: JsonElement?): Set<String> {
        if (config == null) return emptySet()
        return baseReviewFiles.paths(AiPluginConfig.fromPluginConfig(config)) + conventionEnrichers().flatMap { it.basePaths(config) }
    }

    /** Hands the MR base files to the convention enricher and the Duo instructions and learnings to the AI rule module. */
    override suspend fun onBaseFiles(files: Map<String, String>) {
        conventionEnrichers().forEach { it.useBaseFiles(files) }
        val yaml = registry.configFormats().firstOrNull { "yaml" in it.fileExtensions }
        val instructions = baseReviewFiles.read(files, pluginConfigRef.value, yaml, logger)
        if (instructions.isNotEmpty()) aiRule()?.addReviewInstructions(instructions)
    }

    private fun conventionEnrichers() = registry.aiContextEnrichers().filterIsInstance<ConventionContextEnricher>()

    private fun anyAnalyzerEnabled(
        config: JsonElement,
        filter: (AiAnalyzerModule) -> Boolean = { true },
    ): Boolean {
        val pluginConfig = AiPluginConfig.fromPluginConfig(config)
        return registry.aiAnalyzers().any { filter(it) && pluginConfig.isFeatureEnabled(it.featureKey, it.defaultEnabled, config) }
    }

    // -- Incremental cache ----------------------------------------------------

    private val incrementalCache = AiIncrementalCache(registry, ::aiRule)

    /** Full config element from [onConfigured]; part of the incremental cache key. */
    private val configuredRawConfig = atomic<JsonElement?>(null)

    override fun getCachedFindings(
        mr: MergeRequest,
        config: JsonElement?,
        logger: Logger,
    ): List<Finding>? {
        if (config == null) return null
        return incrementalCache.cachedFindings(mr, pluginConfigRef.value, injectedPersistence.value, incrementalConfigHash(config), logger)
    }

    override fun saveIncrementalState(
        mr: MergeRequest,
        findings: List<Finding>,
    ) = incrementalCache.save(findings, logger)

    /**
     * Returns the current [IncrementalContext] for this pipeline run, or null
     * if incremental validation is not active.
     *
     * Used by the pipeline to merge cached findings into the reporter and
     * to extract [IncrementalContext.changedFiles] for scoped inline comment posting.
     */
    fun getIncrementalContext(): IncrementalContext? = incrementalCache.context

    override fun incrementalChangedFiles(): Set<String>? = incrementalCache.changedFiles()

    override fun incrementalCachedFindings(): List<Finding>? = incrementalCache.partialCachedFindings()

    /**
     * Cache-key hash over everything that can change the findings of an unchanged MR: the full
     * config file, the resolved `ai:` block and the AI plugin config derived from it.
     */
    private fun incrementalConfigHash(aiBlock: JsonElement): Long = incrementalConfigHash(configuredRawConfig.value, aiBlock)

    /** The run computes the rule config again on the enrichment re-init; only the first computation warns. */
    private val ruleConfigWarned = atomic(false)

    override fun computeRuleModuleConfig(configElement: JsonElement?): JsonElement? {
        val element = configElement ?: return null
        return AiPluginConfig.topLevelAiBlockToPluginConfigElement(element, logger.takeUnless { ruleConfigWarned.getAndSet(true) })
    }

    // -- Private helpers -------------------------------------------------------

    private suspend fun appendDigestSummary(
        reportFindings: MutableList<Finding>,
        findings: List<Finding>,
        mr: MergeRequest,
    ) {
        val state = runState.value
        val enabledReports = state?.enabledReports ?: emptySet()
        if ("digest" !in enabledReports) return

        @Suppress("TooGenericExceptionCaught") // boundary: digest is best-effort
        try {
            val digestGen = SmartDigestGenerator(
                budgeted("digest", mr, findings, active = true),
                language = LanguagePromptOverlays.responseLanguageOf(configuredRawConfig.value),
            )
            val digest = digestGen.generate(findings, DigestAudience.DEVELOPER)
            reportFindings.add(
                Finding(
                    severity = Severity.INFO,
                    message = digest.summary.trim().take(DIGEST_LOG_LIMIT),
                    rule = "report:digest",
                ),
            )
        } catch (e: Exception) {
            // boundary: optional AI step must not fail the pipeline
            e.rethrowCancellation()
            logger.warn("AI digest generation failed: ${e.message}")
        }
    }

    private fun appendCognitiveLoadSummary(
        reportFindings: MutableList<Finding>,
        enabledReports: Set<String>,
    ) {
        if ("cognitive-load" !in enabledReports) return

        val cognitiveLoad = CognitiveLoadEstimator.estimate(runState.value?.mr ?: return)
        val cognitiveRecommendation = cognitiveLoad.recommendation ?: return
        reportFindings.add(
            Finding(
                severity = Severity.INFO,
                message = cognitiveRecommendation,
                rule = "report:cognitive-load",
                metadata = mapOf(
                    "estimatedMinutes" to cognitiveLoad.estimatedReviewMinutes.toInt().toString(),
                    "riskLevel" to cognitiveLoad.riskLevel.name,
                ),
            ),
        )
    }

    /** The `ai.processRisk` INFO finding, when the block is enabled. */
    private fun processRiskFinding(state: ValidationRunState?): Finding? {
        val riskConfig = pluginConfigRef.value.processRisk?.takeIf { it.enabled } ?: return null
        val risk = state?.processRisk ?: return null
        return ProcessRiskAssessment.finding(risk, riskConfig.factors, pipelineConfig.thresholds.highRisk.fraction)
    }

    /** The MR's process-risk composite for `ai.routing`, once [onPostFetch] ran. */
    fun processRiskScore(): Double? = runState.value?.processRisk?.composite

    private fun appendProcessRiskSummary(
        reportFindings: MutableList<Finding>,
        state: ValidationRunState?,
        enabledReports: Set<String>,
    ) {
        if ("process-risk" !in enabledReports) return

        val risk = state?.processRisk ?: return
        if (risk.composite <= pipelineConfig.thresholds.highRisk.fraction) return
        reportFindings.add(
            Finding(
                severity = Severity.INFO,
                message = "Process risk: HIGH (composite=${risk.composite.formatDecimals(2)})",
                rule = "report:process-risk",
            ),
        )
    }

    private fun appendReportFindings(
        reportFindings: MutableList<Finding>,
        generatedReports: Map<String, String>,
    ) {
        for ((name, content) in generatedReports) {
            if (content.isBlank()) continue
            val contentType = when {
                name.endsWith("-html") -> CONTENT_TYPE_HTML
                name.endsWith(".json") || name == "check-run-summary" || name == CheckRunAnnotationReport.REPORT_ID -> CONTENT_TYPE_JSON
                else -> "text/markdown"
            }
            val extension = when (contentType) {
                CONTENT_TYPE_HTML -> ".html"
                CONTENT_TYPE_JSON -> ".json"
                else -> ".md"
            }
            reportFindings.add(
                Finding(
                    severity = Severity.INFO,
                    message = "Report: $name",
                    rule = "report:$name",
                    attachments = listOf(
                        Attachment(
                            filename = "$name$extension",
                            content = content,
                            contentType = contentType,
                        ),
                    ),
                ),
            )
        }
    }

    /** JIT defect prediction with the configured `ai.defectPredictor` weights and thresholds. */
    internal fun predictDefect(mr: MergeRequest): DefectPrediction = JitDefectPredictor.predict(mr, defectPredictorConfig.value)

    /** CLI-injected stats dir wins over `ai.statistics.dir`; `ai.statistics.maxHistory` bounds retention. */
    private fun statisticsStore(): StatisticsStore {
        val cfg = pluginConfigRef.value.statistics
        return StatisticsStore(statsDirRef.value ?: cfg.dir, historyWindow())
    }

    private fun historyWindow(): Int = pluginConfigRef.value.statistics.maxHistory

    @Suppress("TooGenericExceptionCaught") // boundary: statistics must not crash pipeline
    private fun collectStatistics() {
        val state = runState.value ?: return
        try {
            val store = statisticsStore()
            StatisticsCollector(store).collect(state.mrId, state.findings, platformIdRef.value, state.mr?.author?.username)
            val config = pluginConfigRef.value
            config.calibration?.takeIf { it.enabled }?.let { calibration ->
                CalibrationEngine
                    .report(store.loadAll(), calibration, Clock.System.now().epochSeconds, config.research.evtAutoThreshold)
                    .takeIf { it.isNotEmpty() }
                    ?.let { logger.info(it.joinToString("\n")) }
            }
        } catch (e: Exception) {
            // boundary: optional AI step must not fail the pipeline
            e.rethrowCancellation()
            logger.warn("Statistics collection failed: ${e::class.simpleName}: ${e.message}")
            // best-effort: statistics errors must never fail the pipeline
        }
        val mr = state.mr
        if (mr != null) {
            val defect = predictDefect(mr)
            val persistence = injectedPersistence.value
            val defectModel = AdaptiveDefectModel(initial = persistence?.loadDefectModelState() ?: DefectModelState())
            val drift = defectModel.update(defect.probability)
            persistence?.saveDefectModelState(defectModel.snapshot())
            if (drift.driftDetected) {
                logger.debug(
                    "Concept drift detected in defect model (deviation=${drift.deviation.formatDecimals(1)})",
                )
            }
        }
    }

    companion object {
        /** Module ID for registry lookup. */
        const val ID = "io.karandash.ai.pipeline-extension"
        private const val DIGEST_LOG_LIMIT = 300
        private const val AUXILIARY_BUDGET_RULE = "ai:auxiliary-budget"
        private const val CONTENT_TYPE_HTML = "text/html"
        private const val CONTENT_TYPE_JSON = "application/json"
        private val EXECUTION_PROFILE_KEYS = setOf("executionProfile", "executionProfiles")

        /**
         * the tool [version] is part of the key, so an upgrade that ships new built-in prompts does not reuse
         * findings cached by the previous version for unchanged files.
         */
        internal fun incrementalConfigHash(
            rawConfig: JsonElement?,
            aiBlock: JsonElement,
            version: String = Version.VERSION,
        ): Long {
            val pluginConfig = AiPluginConfig.topLevelAiBlockToPluginConfigElement(aiBlock)
            return sha256First64Bits("$version\n$rawConfig\n$aiBlock\n$pluginConfig")
        }

        /** This run's handle (from the run services), or the registry instance outside a run. */
        suspend fun forCurrentRun(registry: ModuleRegistry): AiPipelineExtension? =
            (RunScopedServices.current()?.get(RunServiceKeys.PIPELINE_EXTENSIONS) ?: registry.pipelineExtensions())
                .filterIsInstance<AiPipelineExtension>()
                .firstOrNull()

        /**
         * Extracts enabled module IDs from a named sub-section of the AI config block.
         *
         * Mirrors [io.karandash.config.pipeline.ModuleEnablementResolver] logic:
         * - Boolean shorthand: `smart-digest: true` -> enabled
         * - Object with `enabled: false` -> disabled
         * - Object without `enabled:` -> enabled by presence
         *
         * @param config Raw AI config element (may be the full config with `ai:` key or AI block itself)
         * @param sectionKey Sub-section name within the AI block (e.g. "reports", "metrics")
         * @return Set of enabled module IDs (kebab-case as written in config)
         */
        fun resolveEnabledModuleIds(
            config: JsonElement?,
            sectionKey: String,
        ): Set<String> {
            val obj = config as? JsonObject ?: return emptySet()
            val aiBlock = obj["ai"] as? JsonObject ?: obj
            val section = aiBlock[sectionKey] as? JsonObject ?: return emptySet()
            val enabled = mutableSetOf<String>()
            for ((moduleId, value) in section) {
                val isEnabled = when {
                    value is JsonPrimitive -> {
                        value.booleanOrNull == true
                    }

                    value is JsonObject -> {
                        val explicit = (value["enabled"] as? JsonPrimitive)?.booleanOrNull
                        explicit ?: true // presence = enabled
                    }

                    else -> {
                        false
                    }
                }
                // the config chain camelCases these keys ("cognitive-load" -> "cognitiveLoad"); module IDs are kebab-case
                if (isEnabled) enabled.add(ConfigKeyNormalizer.camelToKebabCase(moduleId))
            }
            return enabled
        }

        /**
         * Resolves [AiPluginConfig] from a raw top-level config element.
         *
         * Extracts the `ai:` block and converts it to typed config.
         * Returns null if no AI config is present.
         */
        fun resolveAiConfig(config: JsonElement?): AiPluginConfig? {
            val obj = config as? JsonObject ?: return null
            val aiBlock = obj["ai"] as? JsonObject ?: obj
            return AiPluginConfig.fromPluginConfig(
                AiPluginConfig.topLevelAiBlockToPluginConfigElement(aiBlock),
            )
        }

        private fun buildHookExecutor(
            rawElement: JsonElement,
            aiService: AiService,
        ): AiHookExecutor? {
            val rawObj = rawElement as? JsonObject ?: return null
            val aiBlock = rawObj["ai"] as? JsonObject ?: rawObj
            val hooksRaw = aiBlock["hooks"] as? JsonObject ?: return null
            if (hooksRaw.isEmpty()) return null

            val hooks = AiPluginConfig
                .fromPluginConfig(aiBlock as JsonElement)
                .hooks
            if (hooks.isEmpty()) return null

            val defaultModel = (aiBlock["model"] as? JsonPrimitive)?.content
                ?: AiDefaultModels.cheapFor((aiBlock["provider"] as? JsonPrimitive)?.content)
            return AiHookExecutor(aiService, hooks, defaultModel)
        }
    }
}

/**
 * Immutable snapshot of per-validation-run state shared across pipeline stages.
 *
 * Captured incrementally: [AiPipelineExtension.onPostFetch] writes [mrId], [mr], and
 * [processRisk]; [AiPipelineExtension.afterValidation] adds [findings], [config],
 * [enabledReports], and [enabledMetrics]. Held in an [kotlinx.atomicfu.AtomicRef] so
 * replacement between runs is visible without additional synchronization.
 */
@Serializable
internal data class ValidationRunState(
    val mrId: String = "",
    val mr: MergeRequest? = null,
    val processRisk: ProcessRiskMetrics? = null,
    val findings: List<Finding> = emptyList(),
    val config: JsonElement? = null,
    val enabledReports: Set<String> = emptySet(),
    val enabledMetrics: Set<String> = emptySet(),
)
