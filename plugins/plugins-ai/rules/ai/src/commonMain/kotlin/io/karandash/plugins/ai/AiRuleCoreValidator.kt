@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.run.dumpJson
import io.karandash.config.keys.ModuleIds
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.DiffContextBuilder
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.FindingLocationValidator
import io.karandash.plugins.ai.core.ScopeMatcher
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.config.PromptDumpMode
import io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics
import io.karandash.plugins.ai.core.hunksPast
import io.karandash.plugins.ai.core.router.ModelRouter
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.PerRuleContextResolver
import io.karandash.plugins.ai.dispatch.AiRuleDispatcher
import io.karandash.plugins.ai.dispatch.ChunkBudget
import io.karandash.plugins.ai.dispatch.DispatchPlanDump
import io.karandash.plugins.ai.dispatch.DryRunRecorder
import io.karandash.plugins.ai.dispatch.MrMap
import io.karandash.plugins.ai.dispatch.NoJsonRecovery
import io.karandash.plugins.ai.dispatch.PreprocessResult
import io.karandash.plugins.ai.dispatch.PromptDump
import io.karandash.plugins.ai.dispatch.RuleCallOverride
import io.karandash.plugins.ai.dispatch.RuleTemperature
import io.karandash.plugins.ai.dispatch.TieredExtras
import io.karandash.plugins.ai.dispatch.UsageMeteringAiProvider
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.state.snapshot.DiffDeltaCalculator
import io.karandash.utils.finding.FindingFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** [AiContextBlock.source] of the MR text a configured prompt references. */
private const val MR_PROMPT_DATA = "mr-prompt-data"

/**
 * Runs one AI rule after [AiRuleModule.validate] has passed the skip and budget checks:
 * preprocess, prompt assembly, dispatch, location validation and post-processing.
 *
 * Holds no state of its own beyond [mrStateLock]; [AiRuleModule] owns the atomic init/run state and hands in
 * accessors, since atomicfu fields cannot leave their declaring class.
 */
internal class AiRuleCoreValidator(
    private val registry: ModuleRegistry,
    private val enrichmentScope: AiRuleEnrichmentScope,
    private val states: StateAccess,
) {
    private val mrStateLock = Mutex()

    /** Accessors over [AiRuleModule]'s atomic state. */
    class StateAccess(
        val readInit: () -> AiRuleInitState?,
        val updateInit: ((AiRuleInitState) -> AiRuleInitState) -> Unit,
        val readRun: () -> AiRuleState,
        val updateRun: ((AiRuleState) -> AiRuleState) -> Unit,
    )

    /** Input of one [execute] call. */
    class Request(
        val ruleId: String,
        val redactedMr: MergeRequest,
        val budget: TokenBudget,
        val effectiveBudget: TokenBudget,
        val aiProvider: AiProvider,
        val callOverride: RuleCallOverride = RuleCallOverride(),
    ) {
        /** review-comments on its own share: bounded by that share alone, never refused by the run budget. */
        val primary: Boolean get() = isPrimary(ruleId, budget, effectiveBudget)
    }

    suspend fun execute(request: Request): List<Finding> {
        val started = TimeSource.Monotonic.markNow()
        val ruleId = request.ruleId
        val state = states.readInit() ?: return AiRuleErrorHandlers.errorFinding(ruleId, AiRuleErrorHandlers.ERR_NOT_INITIALIZED)
        initializeMrStateOnFirstRun(request.redactedMr, state)
        val s = states.readInit() ?: return AiRuleErrorHandlers.errorFinding(ruleId, AiRuleErrorHandlers.ERR_NOT_INITIALIZED)
        val snapshotManager = checkNotNull(s.snapshotManager) {
            "SnapshotManager must be set after MR key initialization"
        }
        repeatDismissals(ruleId, s)?.let { return skip(ruleId, "dismissed $it times previously", s) }

        val preprocessResult = s.mrPreprocessor.preprocessMrForRule(ruleId, request.redactedMr, s.aiRuleConfig, snapshotManager)
        val ready = when (preprocessResult) {
            is PreprocessResult.Skipped -> return skip(ruleId, preprocessResult.reason, s)
            is PreprocessResult.Ready -> preprocessResult
        }
        val filteredMr = ready.mr

        val scopedEnrichment = enrichmentScope.scoped(ruleId, s.enrichment, s.ruleEnrichmentBindingPrefixes)
        val missingRequired = enrichmentScope.missingRequired(ruleId, scopedEnrichment)
        if (missingRequired.isNotEmpty()) {
            return skip(ruleId, "missing required enrichment: ${missingRequired.joinToString(", ")}", s)
        }
        val dryRun = if (s.pluginCfg.dryRunAi) DryRunRecorder(request.aiProvider) else null
        val meteredProvider = UsageMeteringAiProvider(dryRun ?: request.aiProvider)
        val (built, plan) = buildDispatchInput(request, s, filteredMr, scopedEnrichment, meteredProvider, dryRun)
        // The engine stops the rule at ai.executionTimeout; the chunks stop short of it so the finished ones are kept.
        val deadline = s.pluginCfg.executionTimeout?.let { started + it.toKotlinDuration() * DEADLINE_SHARE }
        val dispatchInput = built.copy(excluded = ready.excluded, deadline = deadline)
        val tokensBefore = request.effectiveBudget.totalConsumed()
        s.runtimeContext.logger.debug("AI plugin [$ruleId]: starting (model=${dispatchInput.model})")
        val dispatchResult = s.dispatcher.dispatch(dispatchInput)
        dumpJson("ai/$ruleId/plan.json") {
            plan.copy(diff = dispatchResult.diff, cuts = dispatchResult.cuts, skippedChunks = dispatchResult.skippedChunks)
        }
        dumpJson("ai/$ruleId/dispatch-findings.json") { dispatchResult.findings }
        val samples = dispatchResult.sampleTokens
            .takeIf { it > 0 }
            ?.let { mapOf(ruleId to it) }
            .orEmpty()
        if (dispatchResult.refusals.isNotEmpty() || samples.isNotEmpty()) {
            states.updateInit {
                it.copy(
                    budgetPlan = it.budgetPlan?.let { p ->
                        p.copy(refusals = p.refusals + dispatchResult.refusals, sampleTokens = p.sampleTokens + samples)
                    },
                )
            }
            states.readInit()?.budgetPlan?.let { plan -> dumpJson("ai/budget-plan.json") { plan } }
        }
        if (dryRun != null) return dryRunReport(ruleId, dispatchInput.model, dryRun, s)

        val usage = meteredProvider.usage(budgetTokens = request.effectiveBudget.totalConsumed() - tokensBefore)
        states.updateRun { it.copy(ruleTimings = it.ruleTimings + (ruleId to dispatchResult.durationMs)) }
        dispatchResult.coverage?.let { coverage ->
            states.updateRun { it.copy(ruleCoverage = it.ruleCoverage + (ruleId to coverage)) }
            coverage.skipReason?.let { skip(ruleId, it, s) }
        }

        val validatedFindings = validateLocations(ruleId, dispatchResult.findings, request.redactedMr, s)
            .let { withInstruction(it, filteredMr, s) }
        dumpJson("ai/$ruleId/located-findings.json") { validatedFindings }
        s.skipEvaluator.canaryTracker.recordFindings(ruleId, validatedFindings, s.feedbackStore)
        val postResult = s.postProcessor.postProcess(
            ruleId = ruleId,
            rawFindings = validatedFindings,
            durationMs = dispatchResult.durationMs,
            usage = usage,
            feedbackEnabled = s.pluginCfg.feedbackEnabled,
            feedbackStore = s.feedbackStore,
            defaultModel = s.model,
        )

        val findings = postResult.findings.map { it.withWhy(ruleId, dispatchInput.model, scopedEnrichment.availableIds) }
        dumpJson("ai/$ruleId/post-processed-findings.json") { findings }
        states.updateRun { cur ->
            cur
                .copy(
                    accumulatedFindings = cur.accumulatedFindings + findings,
                    ruleTokenCounts = cur.ruleTokenCounts + (ruleId to (cur.ruleTokenCounts[ruleId] ?: 0) + postResult.tokensUsed),
                    totalEstimatedCostUsd = cur.totalEstimatedCostUsd + postResult.estimatedCostUsd,
                    ruleInputTokens = cur.ruleInputTokens + (ruleId to (cur.ruleInputTokens[ruleId] ?: 0) + postResult.inputTokens),
                    ruleOutputTokens = cur.ruleOutputTokens + (ruleId to (cur.ruleOutputTokens[ruleId] ?: 0) + postResult.outputTokens),
                    sampleTokens = cur.sampleTokens + samples.mapValues { (id, n) -> (cur.sampleTokens[id] ?: 0) + n },
                ).withAnalyzerRun(ruleId, dispatchInput.model, postResult)
        }
        return findings
    }

    private suspend fun buildDispatchInput(
        request: Request,
        s: AiRuleInitState,
        filteredMr: MergeRequest,
        scopedEnrichment: EnrichmentContext,
        meteredProvider: UsageMeteringAiProvider,
        dryRun: DryRunRecorder?,
    ): Pair<AiRuleDispatcher.DispatchInput, DispatchPlanDump> {
        val ruleId = request.ruleId
        val pipelineExtension = AiPipelineExtension.forCurrentRun(registry)
        val priorIncrementalCtx = pipelineExtension?.getIncrementalContext()
        val extras = s.promptAssembler.assembleExtras(
            ruleId = ruleId,
            mr = filteredMr,
            changes = filteredMr.changes,
            pluginCfg = s.pluginCfg,
            aiRuleConfig = s.aiRuleConfig,
            strictness = s.strictness,
            feedbackStore = s.feedbackStore,
            incrementalContext = priorIncrementalCtx,
            enricherConfigJson = mergedEnricherConfigJson(s.runtimeContext.pluginConfig, scopedEnrichment),
            needsScopeDetection = { id -> registry.findAiAnalyzer(id)?.needsScopeDetection ?: false },
        )
        val matchedScopeNames = ScopeMatcher.matchScopes(filteredMr.changes, s.pluginCfg.scopes).map { it.name }
        // A chunked diff wants one chunk per call, not the whole MR.
        val chunked = ChunkBudget(registry).chunked(ruleId, s.pluginCfg)
        val chunkChars = s.pluginCfg.chunking
            ?.chunkSize
            ?.takeIf { chunked } ?: Int.MAX_VALUE
        // Counted as the dispatcher's hold counts it (non-ASCII text at TokenUnit's denser rate), so a plan that fills
        // the budget is never refused at the hold.
        val diffTokensWanted = minOf(
            filteredMr.changes.sumOf { TokenUnit.tokensOf(it.diffContent.orEmpty()) },
            TokenUnit.charsToTokens(minOf(s.aiRuleConfig.maxDiffCharsTotal, chunkChars)),
        )
        val logger = s.runtimeContext.logger
        val routedModel = ModelRouter.route(s.pluginCfg.routing, ruleId, pipelineExtension?.processRiskScore())
        routedModel?.let { logger.debug("AI plugin [$ruleId]: ai.routing picked model=$it") }
        val model = request.callOverride.model ?: s.perRuleModels[ruleId] ?: routedModel ?: s.model
        val tiered = extras
            .plan(
                systemPrompt = systemPromptOf(ruleId, filteredMr, matchedScopeNames, s),
                metadata = DiffContextBuilder.mrMetadata(filteredMr),
                maxInputTokens = s.calibration.adjust(model, ChunkBudget.contextBudgetOf(s.pluginCfg, model)),
                diffTokensWanted = diffTokensWanted,
                reserveOutputTokens = s.pluginCfg.budget.outputReserveTokens,
                remainingTokens = remainingTokensOf(request, chunked, filteredMr, s),
                splittableDiff = chunked && s.pluginCfg.largeMr.chunkContext,
                minDiffTokens = if (chunked) ChunkBudget.MIN_DIFF_TOKENS else 0,
            ).let { withHunksCut(it, filteredMr, diffTokensWanted, chunked) }
        recordCoverage(ruleId, tiered, logger)
        val combinedExtra = tiered.combinedExtra
        // The diff gets what T0 and the plan left it; a smaller cap than `limits` also shrinks the chunks.
        val aiRuleConfig = if (tiered.skipReason == null && tiered.diffTokens < diffTokensWanted) {
            s.aiRuleConfig.copy(maxDiffCharsTotal = ChunkBudget.charsOf(tiered.diffTokens, filteredMr).coerceAtLeast(1))
        } else {
            s.aiRuleConfig
        }
        val resolvedPrompt = s.promptAssembler.resolvePromptForRule(
            ruleId = ruleId,
            mr = filteredMr,
            combinedExtra = combinedExtra,
            matchedScopeNames = matchedScopeNames,
            promptSources = s.promptSources,
            promptResolver = s.promptResolver,
            pluginCfgContext = s.pluginCfg.context,
        )
        // Analyzers call through AiService, so the rule's `ruleProviders` entry and usage meter apply there too.
        val ruleService = s.aiService?.forRule(
            ruleId,
            s.normalizedRuleProviders[ruleId],
            ModuleIds.AI_ANALYSIS,
            onResponse = { meteredProvider.record(it.promptTokens, it.completionTokens, it.cacheReadTokens, it.cacheWriteTokens) },
            dryRunSink = dryRun?.let { it::record },
            promptSink = s.pluginCfg.debug
                .takeIf { it.prompt == PromptDumpMode.ARTIFACT }
                ?.let { PromptDump(it.promptDir, ruleId, s.runtimeContext.logger)::write },
        )
        val noJsonRecovery = s.pluginCfg.filter?.attemptJsonRecovery == false
        val temperature = s.perRuleTemperatures[ruleId]
        val temperedService = ruleService?.let { svc -> temperature?.let { RuleTemperature.Service(svc, it) } ?: svc }
        val callOverride = request.callOverride.copy(systemPromptChars = s.aiRuleConfig.analyzerTokens.maxSystemPromptChars)
        val service = temperedService?.let { svc ->
            callOverride.takeUnless { it.isEmpty }?.let { RuleCallOverride.Service(svc, it) } ?: svc
        }
        val provider = temperature?.let { RuleTemperature.Provider(meteredProvider, it) } ?: meteredProvider
        return AiRuleDispatcher.DispatchInput(
            ruleId = ruleId,
            mr = filteredMr,
            concurrencySemaphore = s.concurrencySemaphore,
            aiProvider = if (noJsonRecovery) NoJsonRecovery.Provider(provider, ruleId, logger) else provider,
            budget = request.effectiveBudget,
            runBudget = request.budget.takeIf { it !== request.effectiveBudget },
            gateRun = !request.primary,
            model = model,
            config = s.runtimeContext.pluginConfig,
            resolvedPrompt = resolvedPrompt?.systemPrompt,
            aiService = service?.let { if (noJsonRecovery) NoJsonRecovery.Service(it, ruleId, logger) else it },
            aiRuleConfig = aiRuleConfig,
            pluginCfg = s.pluginCfg,
            contextProcessors = s.contextProcessors,
            // Accumulated in rule completion order (parallel rules, and review-comments also emits code-suggestions):
            // sort so the prompt built from them does not depend on scheduling.
            accumulatedFindings = states.readRun().let { it.accumulatedFindings + it.deterministicFindings }.distinct().sortedWith(
                compareBy({ it.rule }, { it.message }, { it.location?.toString() }),
            ),
            customRules = s.customRules,
            extraInstructions = combinedExtra,
            plannedContextTokens = tiered.contextTokens,
            enrichment = scopedEnrichment,
            fullMr = request.redactedMr,
            tierSkip = tiered.skipReason,
            contextDropped = tiered.contextDropped,
            // MR text the configured prompt references travels in the user message, never the system prompt.
            contextBlocks = tiered.contextBlocks + listOfNotNull(
                resolvedPrompt?.mrData?.let { AiContextBlock(heading = "", content = it, priority = 0, source = MR_PROMPT_DATA) },
            ),
            calibration = s.calibration,
        ) to DispatchPlanDump.of(tiered, diffTokensWanted, routedModel, model)
    }

    /**
     * Tokens this call may plan with. A chunked call is sized as the budget plan sized it, never a live read of what
     * other rules spent: [ChunkBudget.chunkCallCap] less the map of other chunks.
     */
    private fun remainingTokensOf(
        request: Request,
        chunked: Boolean,
        mr: MergeRequest,
        s: AiRuleInitState,
    ): Int {
        if (chunked) {
            val map = MrMap.tokensOf(mr.changes, s.pluginCfg.largeMr.mrMapMaxFiles)
            return ChunkBudget.chunkCallCap(request.effectiveBudget.callTokens, s.pluginCfg) - map
        }
        return if (request.primary) {
            request.effectiveBudget.remaining()
        } else {
            minOf(request.effectiveBudget.remaining(), request.budget.remaining())
        }
    }

    /** Logs the request's context coverage line at INFO and keeps it for provenance and `diagnostic.json`. */
    private fun recordCoverage(
        ruleId: String,
        tiered: TieredExtras,
        logger: Logger,
    ) {
        val line = tiered.coverage?.describe() ?: return
        logger.info("AI plugin [$ruleId]: context $line")
        states.updateRun { it.copy(ruleContext = it.ruleContext + (ruleId to line)) }
    }

    /** Counts the diff hunks a whole-diff cut leaves out into the coverage; a chunked diff loses none. */
    private fun withHunksCut(
        tiered: TieredExtras,
        mr: MergeRequest,
        diffTokensWanted: Int,
        chunked: Boolean,
    ): TieredExtras {
        val coverage = tiered.coverage ?: return tiered
        if (chunked || tiered.diffTokens >= diffTokensWanted) return tiered
        return tiered.copy(coverage = coverage.copy(diffHunksCut = hunksPast(mr.changes, TokenUnit.tokensToChars(tiered.diffTokens))))
    }

    /** The rule's prompt without the extras, as T0: what [plannedSystemPrompt] says the analyzer sends. */
    private suspend fun systemPromptOf(
        ruleId: String,
        mr: MergeRequest,
        matchedScopeNames: List<String>,
        s: AiRuleInitState,
    ): String {
        val configured = s.promptAssembler.resolvePromptForRule(
            ruleId = ruleId,
            mr = mr,
            combinedExtra = null,
            matchedScopeNames = matchedScopeNames,
            promptSources = s.promptSources,
            promptResolver = s.promptResolver,
            pluginCfgContext = s.pluginCfg.context,
        )
        return plannedSystemPrompt(configured?.systemPrompt, registry.findAiAnalyzer(ruleId))
    }

    /** With exactly one matched `ai.review.instructions` entry, [findings] name it in `metadata.instruction`. */
    private fun withInstruction(
        findings: List<Finding>,
        mr: MergeRequest,
        s: AiRuleInitState,
    ): List<Finding> {
        val name = ScopeMatcher.matchInstructions(mr.changes, s.pluginCfg.review.instructions).singleOrNull()?.name
            ?: return findings
        return findings.map { it.copy(metadata = it.metadata + (AiFindingMetadataKeys.INSTRUCTION to name)) }
    }

    /** Dismissal count when `ai.feedback.skipRepeatFindingTypes` skips [ruleId], else null. */
    private fun repeatDismissals(
        ruleId: String,
        s: AiRuleInitState,
    ): Int? {
        if (!s.pluginCfg.feedbackEnabled || !s.pluginCfg.feedbackSkipRepeatFindingTypes) return null
        return s.feedbackStore.dismissCountByRule()[ruleId]?.takeIf { it >= FeedbackStore.REPEAT_DISMISSAL_THRESHOLD }
    }

    /** Loads the MR state once per run: rules validate in parallel, so the first one loads it under [mrStateLock]. */
    private suspend fun initializeMrStateOnFirstRun(
        mr: MergeRequest,
        state: AiRuleInitState,
    ) {
        if (state.currentMrKey != null) return
        mrStateLock.withLock {
            val current = states.readInit() ?: return
            if (current.currentMrKey != null) return
            // Same config hash the incremental cache keyed on, so a config-only change is not delta-skipped.
            val configHash = AiPipelineExtension
                .forCurrentRun(registry)
                ?.getIncrementalContext()
                ?.currentSnapshot
                ?.get(DiffDeltaCalculator.CONFIG_KEY)
            val loaded = AiMrStatePersistence.load(mr, current, configHash)
            // Planned before currentMrKey is set: rules that start alongside wait on the lock, so none dispatches unplanned.
            val ledger = current.tokenBudget?.let { run ->
                ChunkBudget(registry)
                    .ledger(
                        ChunkBudget.Input(
                            current.perRuleBudgets,
                            run,
                            mr,
                            current.pluginCfg,
                            current.aiRuleConfig,
                            current.model,
                            current.customRules,
                            current.perRuleModels,
                            current.calibration,
                        ),
                        current.runtimeContext.logger,
                    ).also { dumpJson("ai/budget-plan.json") { it } }
            }
            states.updateInit { s ->
                s.copy(
                    currentMrKey = loaded.currentMrKey,
                    snapshotManager = loaded.snapshotManager,
                    feedbackStore = loaded.feedbackStore,
                    budgetPlan = ledger,
                )
            }
        }
    }

    private fun validateLocations(
        ruleId: String,
        findings: List<Finding>,
        redactedMr: MergeRequest,
        s: AiRuleInitState,
    ): List<Finding> {
        val resolvedCq = PerRuleContextResolver.resolveForRule(ruleId, s.aiRuleConfig.contextQuality)
        val validatorConfig = resolvedCq.findingValidator
        if (!resolvedCq.enabled || !validatorConfig.enabled) return findings
        return FindingLocationValidator.validate(
            findings = findings,
            mr = redactedMr,
            ruleId = ruleId,
            strict = validatorConfig.strict,
            diagnostics = ContextQualityDiagnostics(s.runtimeContext.logger),
        )
    }

    /** Prompts were built and counted but nothing was sent; the analyzers' output on empty responses is discarded. */
    private fun dryRunReport(
        ruleId: String,
        model: String,
        dryRun: DryRunRecorder,
        s: AiRuleInitState,
    ): List<Finding> {
        val message = "[DRY RUN] AI analysis skipped. Provider: ${s.pluginCfg.provider}, Model: $model. ${dryRun.summary()}"
        s.runtimeContext.log("AI plugin: $ruleId -- $message")
        states.updateRun { it.withSkip(ruleId, "dry run") }
        return listOf(FindingFactory.info(rule = ruleId, message = message))
    }

    private fun skip(
        ruleId: String,
        reason: String,
        s: AiRuleInitState,
    ): List<Finding> {
        s.runtimeContext.log("AI plugin: Skipping $ruleId -- $reason")
        states.updateRun { it.withSkip(ruleId, reason) }
        return emptyList()
    }

    companion object {
        // limit: fixed 90% of ai.executionTimeout; the rest covers location checks and post-processing after dispatch.
        private const val DEADLINE_SHARE = 0.9

        /** review-comments on its own share of the run [budget]; secondary analyzers cannot starve it. */
        fun isPrimary(
            ruleId: String,
            budget: TokenBudget,
            effectiveBudget: TokenBudget,
        ): Boolean = ruleId == AiRuleIds.REVIEW_COMMENTS && effectiveBudget !== budget
    }
}

/** The system prompt [analyzer] sends, as T0 counts it: [configured] or its default prompt, then its response-format block. */
internal fun plannedSystemPrompt(
    configured: String?,
    analyzer: AiAnalyzerModule?,
): String =
    AiRuleAnalyzerUtils.effectivePrompt(
        basePrompt = analyzer?.defaultPrompt.orEmpty(),
        systemPromptOverride = configured,
        responseFormatSuffix = analyzer?.responseFormatSuffix,
    )
