package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextProcessor
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.ChunkResult
import io.karandash.plugins.ai.core.DiffChunk
import io.karandash.plugins.ai.core.DiffChunker
import io.karandash.plugins.ai.core.DiffContextBuilder
import io.karandash.plugins.ai.core.DiffPreprocessor
import io.karandash.plugins.ai.core.PromptTokenCalibration
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics
import io.karandash.plugins.ai.core.pipeline.CertificateType
import io.karandash.plugins.ai.core.pipeline.DirectStrategy
import io.karandash.plugins.ai.core.pipeline.ReasoningStrategy
import io.karandash.plugins.ai.core.pipeline.SemiFormalPromptBuilder
import io.karandash.plugins.ai.core.pipeline.SemiFormalStrategy
import io.karandash.plugins.ai.core.rules.AiContextQualityConfig
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.PerRuleContextResolver
import io.karandash.plugins.ai.core.rules.TruncationStrategy
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.rules.CustomAiRule
import io.karandash.plugins.ai.rules.CustomAiRuleExecutor
import io.karandash.plugins.ai.rules.PreMergeChecksExecutor
import io.karandash.sdk.content.ContentSource
import io.karandash.utils.BoundedCache
import io.karandash.utils.EnrichmentGlobMatcher
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.mr.HunkParser
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.TimedValue
import kotlin.time.measureTimedValue

/**
 * Encapsulates the dispatch pipeline for a single AI rule evaluation.
 *
 * Handles semaphore-based concurrency, heartbeat logging, diff chunking,
 * semi-formal reasoning, and custom rule fallback. Returns findings and
 * elapsed duration via [DispatchResult].
 */
internal class AiRuleDispatcher(
    private val registry: ModuleRegistry,
    private val logger: Logger,
    /** Builds one diff context; tests swap it to count builds. */
    private val buildDiffContext: suspend (DispatchInput, AiContextQualityConfig) -> PrecomputedDiffContext = { input, quality ->
        DiffContextBuilder.build(
            mr = input.mr,
            aiConfig = input.aiRuleConfig,
            contextQuality = quality,
            enrichment = input.enrichment,
            diagnostics = ContextQualityDiagnostics(logger),
            reviewMode = input.pluginCfg.reviewMode,
        )
    },
) {
    /** Diff contexts by their inputs: analyzers reviewing the same MR with the same settings share one build. */
    private val diffContexts = BoundedCache<DiffContextKey, PrecomputedDiffContext>(DIFF_CONTEXT_ENTRIES)

    /** Held while a diff context is looked up and built, so rules sharing a key build it once. */
    private val diffContextBuild = Mutex()

    /**
     * All inputs needed for a single rule dispatch call.
     *
     * Not @Serializable: runtime context holder, holds a non-serializable `EnrichmentContext?`.
     */
    data class DispatchInput(
        val ruleId: String,
        val mr: MergeRequest,
        val concurrencySemaphore: Semaphore,
        val aiProvider: AiProvider,
        val budget: TokenBudget,
        val model: String,
        val config: kotlinx.serialization.json.JsonElement,
        val resolvedPrompt: String?,
        val aiService: AiService?,
        val aiRuleConfig: AiRuleConfig,
        val pluginCfg: AiPluginConfig,
        val contextProcessors: List<AiContextProcessor>,
        val accumulatedFindings: List<Finding>,
        val customRules: List<CustomAiRule>,
        /**
         * Assembled extra instructions (user `extraInstructions` + scopes, strictness, language,
         * cross-file context, feedback, prior findings, enricher blocks). Sent to the analyzer when
         * no [resolvedPrompt] embeds them already.
         */
        val extraInstructions: String? = null,
        /** Rule-scoped enrichment; feeds the hotspot signal of priority truncation. */
        val enrichment: EnrichmentContext? = null,
        /** The MR before per-rule filtering and chunking; analyzer metadata states its file statistics. */
        val fullMr: MergeRequest? = null,
        /** Paths of the rule's MR before chunking, set on a chunk; null when [mr] is the whole rule MR. */
        val rulePaths: Set<String>? = null,
        /** The run budget when [budget] is a per-rule budget; the gate checks both. */
        val runBudget: TokenBudget? = null,
        /** False for the primary analyzer on its own share: [runBudget] is charged but cannot refuse its calls. */
        val gateRun: Boolean = true,
        /** Why the rule's T0 prompt cannot fit `ai.budget`; set, the rule is a recorded skip with no model call. */
        val tierSkip: String? = null,
        /** T2 context sections the tier plan dropped to fit `ai.budget`; recorded in the ledger. */
        val contextDropped: List<String> = emptyList(),
        /** Tokens of the planned prompt besides the diff (T0 and kept T2 context); the gate reserves it per call. */
        val plannedContextTokens: Int = 0,
        /** The MR context shared by every analyzer, sent ahead of each request's own blocks. */
        val contextBlocks: List<AiContextBlock> = emptyList(),
        /** Files preprocessing left out of [mr] whole, path to reason; recorded as excluded in the ledger. */
        val excluded: Map<String, String> = emptyMap(),
        /** The run's prompt-token calibration; every analyzer call feeds it. */
        val calibration: PromptTokenCalibration = PromptTokenCalibration(),
        /** When the rule's chunks must be done; a chunk still running then is dropped and the finished ones kept. */
        val deadline: TimeSource.Monotonic.ValueTimeMark? = null,
    )

    /** Result returned by [dispatch]. */
    @Serializable
    data class DispatchResult(
        val findings: List<Finding>,
        val durationMs: Long,
        /** What the rule sent to the model; null when the MR had no diff. */
        val coverage: RuleCoverage? = null,
        /** The rule's diff in and out, for the `plan.json` dump. */
        val diff: DiffTotals? = null,
        /** Files sent short or not at all, and how. */
        val cuts: List<DiffCut> = emptyList(),
        /** Chunks no call reviewed, and why. */
        val skippedChunks: List<SkippedChunk> = emptyList(),
        /** Calls the budget gate refused, for `ai/budget-plan.json`. */
        val refusals: List<BudgetRefusal> = emptyList(),
        /** Tokens the extra samples spent on their own budget, outside the run budget. */
        val sampleTokens: Int = 0,
    )

    /**
     * Dispatches a single rule: acquires the concurrency semaphore, runs the
     * heartbeat logger, splits by diff size if needed, and returns findings
     * with elapsed wall-clock time.
     */
    suspend fun dispatch(input: DispatchInput): DispatchResult {
        // Analyzer calls may report from concurrent coroutines: copy-on-write, never mutate in place.
        val skips = SkipLog()
        val timed = logger.withHeartbeat(input.ruleId, input.model) {
            measureTimedValue { dispatchChunked(input, skips) }
        }
        val cuts = skips.cuts.distinct()
        logger.warnCuts(input.ruleId, cuts)
        return DispatchResult(
            findings = timed.value.first,
            durationMs = timed.duration.inWholeMilliseconds,
            coverage = timed.value.second,
            diff = DiffCuts.totals(input.mr, input.fullMr, input.excluded, cuts, skips.skippedChunks),
            cuts = cuts,
            skippedChunks = skips.skippedChunks,
            refusals = skips.refusals,
            sampleTokens = skips.sampleTokens,
        )
    }

    /**
     * Sends the held chunks in parallel; each call still takes a permit of the shared semaphore. Per chunk: its skip
     * log, its findings (null when not sent or timed out) and whether it timed out.
     */
    private suspend fun sendChunks(
        input: DispatchInput,
        chunkInputs: List<DispatchInput>,
        holds: Array<BudgetReservation?>,
    ): List<Triple<SkipLog, Pair<List<Finding>, Int>?, Boolean>> {
        // limit: one deadline shared by all chunks, not split per chunk: they run in parallel, so a split would cut
        // healthy calls; a chunk queued behind a slow one can still time out unsent.
        try {
            return mapParallel(chunkInputs.indices.toList(), input.pluginCfg.largeMr.maxParallel) { index ->
                val skipLog = SkipLog()
                val hold = holds[index] ?: return@mapParallel Triple(skipLog, null, false)
                input.concurrencySemaphore.withPermit {
                    if (!input.budget.canSpend(0)) {
                        Triple(skipLog, null, false)
                    } else {
                        val remaining = input.deadline?.let { -it.elapsedNow() } ?: Duration.INFINITE
                        val findings = withTimeoutOrNull(remaining) { runHeld(chunkInputs[index], skipLog, hold) }
                        Triple(skipLog, findings, findings == null)
                    }
                }
            }
        } finally {
            holds.forEach { it?.close() }
        }
    }

    /**
     * Dispatches a rule, splitting into chunks when the total diff exceeds
     * [AiRuleConfig.maxDiffCharsTotal].
     *
     * When the MR fits in a single chunk the call delegates directly to [dispatchRule].
     * Otherwise the diff is split by [DiffChunker], each chunk is dispatched independently,
     * and the results are merged via [deduplicateChunkFindings].
     */
    private suspend fun dispatchChunked(
        input: DispatchInput,
        skips: SkipLog,
    ): Pair<List<Finding>, RuleCoverage?> {
        val chunkChars = ChunkBudget.chunkChars(input.pluginCfg, input.aiRuleConfig)
        val truncateAbove = minOf(chunkChars, input.aiRuleConfig.maxDiffCharsPerFile)
        // The checklist and MR-level analyzers are one call over the whole MR; per chunk they would repeat.
        val wholeMr = ChunkBudget(registry).wholeMr(input.ruleId)
        val split = if (wholeMr) ChunkResult(emptyList()) else ChunkBudget.split(input.mr, input.pluginCfg, input.aiRuleConfig)
        val chunks = split.chunks
        skips.addExcluded(input.excluded)
        skips.addCuts(DiffCuts.excludedCuts(input.fullMr, input.excluded))
        // One chunk or none sends the whole MR, and DiffContextBuilder reports what its caps leave out.
        if (chunks.size > 1) {
            skips.addOmitted(split.dropped.map { it.describe() })
            skips.addCuts(DiffCuts.droppedCuts(input.mr, chunks, split.dropped))
        }
        val contextDropped = input.contextDropped.toSet()
        // A chunk over the call (one hunk bigger than a chunk) is skipped and disclosed, never cut to fit.
        val fits = chunks.map { it.totalChars <= chunkChars }
        if (input.tierSkip != null || (chunks.size <= 1 && fits.all { it })) {
            return dispatchSingle(input, skips, truncateAbove, contextDropped)
        }

        val totalChars = input.mr.changes.sumOf { it.diffContent?.length ?: 0 }
        logger.info("[ai] rule=${input.ruleId}: diff chunking: $totalChars chars split into ${chunks.size} chunks")
        val rulePaths = input.mr.changes.mapTo(mutableSetOf()) { it.path }
        val chunkInputs = chunks.mapIndexed { index, chunk ->
            val hinted = MrMap.forChunk(
                ChunkDiffNotes.rescope(input.copy(mr = input.mr.copy(changes = chunk.changes), rulePaths = rulePaths)),
                input.mr.changes,
                MrMap.parts(chunks.map { c -> c.changes }, index),
            )
            ContextHints.forCall(hinted, (input.fullMr ?: input.mr).changes)
        }
        // Holds are taken hottest chunk first before any call, so which chunks fit never depends on which finishes first.
        // A held chunk is still skipped when calls before it spent past their holds and overdrew the budget.
        val holds = arrayOfNulls<BudgetReservation>(chunks.size)
        ChunkBudget.hotFirst(chunks, input.mr, input.ruleId, input.aiRuleConfig, input.enrichment).filter { fits[it] }.forEach {
            holds[it] = reserve(chunkInputs[it], skips)
        }
        // Every first call runs before any extra sample; the samples spend a budget of their own, never the first pass's.
        val firsts = sendChunks(input, chunkInputs, holds)
        val samples = SampleAgreement.samplePass(chunkInputs, firsts.map { it.second }, skips, logger, ::sampleCall)
        val sent = firsts.mapIndexed { index, (log, _, timedOut) -> Triple(log, samples[index], timedOut) }
        val timedOut = sent.indices.filter { sent[it].third }.toSet()
        timedOut.forEach { skips.add("chunk ${it + 1} timed out") }
        logger.warnTimedOut(input.ruleId, timedOut.size, chunks.size)
        val refused = chunks.filterIndexed { index, _ -> fits[index] && sent[index].second == null && index !in timedOut }
        skips.addSkipped(DiffCuts.skippedChunks(chunks, fits, sent.map { it.second != null }, chunkChars, timedOut))
        if (refused.isNotEmpty()) {
            logger.warnSkippedChunks(input.ruleId, refused.flatMap { c -> c.changes.map { it.path } }, refused.size, chunks.size)
        }
        logger.warnOversize(input.ruleId, chunks, chunkChars)
        val allFindings = mutableListOf<Finding>()
        val reviewed = mutableListOf<FileChange>()
        val reviewedChunks = chunks.zip(sent).withIndex().count { (index, pair) ->
            val (chunk, result) = pair
            val (chunkSkips, findings, _) = result
            if (findings == null) return@count false
            logger.debug("[ai] rule=${input.ruleId}: chunk ${index + 1} (${chunk.totalChars} chars): ${findings.size} findings")
            allFindings.addAll(findings)
            chunkSkips.reasons.forEach(skips::add)
            chunkSkips.refusals.forEach(skips::addRefusal)
            skips.addOmitted(chunkSkips.omitted)
            skips.addExcluded(chunkSkips.excluded)
            skips.addCuts(chunkSkips.cuts)
            chunkSkips.reasons.isEmpty().also { clean -> if (clean) reviewed.addAll(chunk.changes.filter { chunkSkips.saw(it.path) }) }
        }
        val merged = ReviewCommentsCap.apply(input.ruleId, input.config, deduplicateChunkFindings(allFindings))
        logger.info(
            "[ai] rule=${input.ruleId}: merged ${merged.size} unique findings " +
                "(${allFindings.size - merged.size} duplicates across chunks)",
        )
        val coverage = RuleCoverage.of(
            input.mr.changes,
            reviewed,
            chunks.size,
            chunks.size - reviewedChunks,
            truncateAbove,
            skips.reasons.firstOrNull(),
            skips.omitted,
            contextDropped,
            skips.excluded,
        )
        return merged to coverage.also { logger.warnDropped(input.ruleId, it) }.takeIf { it.files.isNotEmpty() }
    }

    /** The whole MR in one call (or the rule's tier skip): its findings and its coverage. */
    private suspend fun dispatchSingle(
        input: DispatchInput,
        skips: SkipLog,
        truncateAbove: Int,
        contextDropped: Set<String>,
    ): Pair<List<Finding>, RuleCoverage?> {
        if (input.tierSkip != null) logger.warn("[ai] rule=${input.ruleId}: ${input.tierSkip}; rule skipped")
        val all = (input.fullMr ?: input.mr).changes
        val ran = if (input.tierSkip == null) gatedDispatch(ContextHints.forCall(input, all), skips) else null
        val budgetSkip = input.tierSkip == null && ran == null
        if (budgetSkip) logger.warnSkippedChunks(input.ruleId, input.mr.changes.map { it.path }, skipped = 1, total = 1)
        val skipped = ran == null
        if (skipped) skips.addSkipped(listOf(DiffCuts.skipped(0, input.mr.changes, input.tierSkip ?: DiffCuts.BUDGET_EXHAUSTED)))
        val findings = ran.orEmpty()
        val reviewed = if (skipped || skips.reasons.isNotEmpty()) {
            emptyList()
        } else {
            input.mr.changes.filter { skips.saw(it.path) }
        }
        val coverage = RuleCoverage.of(
            input.mr.changes,
            reviewed,
            1,
            if (budgetSkip) 1 else 0,
            truncateAbove,
            input.tierSkip ?: skips.reasons.firstOrNull(),
            skips.omitted,
            contextDropped,
            skips.excluded,
            totalCap = input.aiRuleConfig.maxDiffCharsTotal,
        )
        logger.warnCutByTotal(input.ruleId, reviewed, input.aiRuleConfig.maxDiffCharsTotal)
        // A skip is recorded even on a description-only MR; a run there has no diff coverage to report.
        return findings to coverage.also { logger.warnDropped(input.ruleId, it) }.takeIf { it.files.isNotEmpty() || skipped }
    }

    /**
     * The one budget gate: a rule call runs only when it can hold its planned prompt (context, this call's diff
     * and the output reserve), or the analyzer's estimate when that is larger, on the rule's budget and on the
     * run budget when it is a separate one. Returns null for a refused call, a recorded skip, never an empty result.
     */
    private suspend fun gatedDispatch(
        input: DispatchInput,
        skips: SkipLog,
    ): List<Finding>? {
        val first = input.concurrencySemaphore.withPermit {
            reserve(input, skips)?.let { runHeld(input, skips, it) }
        } ?: return null
        return SampleAgreement.samplePass(listOf(input), listOf(first), skips, logger, ::sampleCall).single()
    }

    /** One extra review-comments sample of [call] on [service], spending [held]. */
    private suspend fun sampleCall(
        call: DispatchInput,
        service: AiService,
        held: AiTokenBudget,
    ): List<Finding> {
        val watch = CallWatch(service, call.calibration, call.pluginCfg.reasoningEffort, logger)
        return dispatchRule(call.copy(aiService = watch), SkipLog(), held)
    }

    /**
     * Holds the call's planned size, or the analyzer's estimate when larger; null, recorded in [skips], when no
     * budget can cover it.
     */
    private fun reserve(
        input: DispatchInput,
        skips: SkipLog,
    ): BudgetReservation? {
        val need = heldOf(input)
        // A call the rule's share cannot hold still runs on the run budget while that has room
        return BudgetReservation.open(input.budget, input.runBudget, need, input.gateRun)
            ?: input.runBudget?.let { BudgetReservation.open(it, null, need) }
            ?: null.also { skips.addRefusal(BudgetRefusal(input.ruleId, need, budgetKnob(input.pluginCfg), DiffCuts.BUDGET_EXHAUSTED)) }
    }

    /** One call's planned size (context, diff and output reserve), or the analyzer's estimate when larger. */
    private fun heldOf(input: DispatchInput): Int {
        val diffTokens = minOf(
            input.mr.changes.sumOf { TokenUnit.tokensOf(it.diffContent.orEmpty()) },
            TokenUnit.charsToTokens(input.aiRuleConfig.maxDiffCharsTotal),
        )
        val call = input.plannedContextTokens + diffTokens + input.pluginCfg.budget.outputReserveTokens
        return maxOf(ChunkBudget.estimateOf(registry, input.ruleId, input.customRules), call)
    }

    /**
     * A half retried after a capped reply skips the rule's gate and holds only the run budget (the rule's
     * budget when there is no separate one); a refusal is warned with the knob and recorded in [skips].
     */
    private suspend fun runHalf(
        half: DispatchInput,
        skips: SkipLog,
    ): List<Finding>? {
        val need = heldOf(half)
        val hold = BudgetReservation.open(half.runBudget ?: half.budget, null, need)
        if (hold != null) return hold.use { dispatchRule(half, skips, it) }
        val knob = budgetKnob(half.pluginCfg)
        logger.warn("[ai] rule=${half.ruleId}: half retry after a capped reply refused, $need tokens over the run budget; raise $knob")
        skips.addRefusal(BudgetRefusal(half.ruleId, need, knob, "half retry after a capped reply"))
        return null
    }

    /**
     * Runs the call on [hold], then closes the hold; a reply cut at its output cap retries each half of the diff
     * once ([retryHalves]). The caller holds a permit of the shared semaphore. Returns sample 1 and the tokens its
     * extra samples need ([SampleAgreement.extraTokens]; 0 when none are taken, as after a capped reply).
     */
    private suspend fun runHeld(
        input: DispatchInput,
        skips: SkipLog,
        hold: BudgetReservation,
    ): Pair<List<Finding>, Int> {
        val watch = input.aiService?.let { CallWatch(it, input.calibration, input.pluginCfg.reasoningEffort, logger) }
        val watched = input.copy(aiService = watch ?: input.aiService)
        val findings = hold.use { dispatchRule(watched, skips, it) }
        if (watch == null) return findings to 0
        if (!watch.truncated) {
            val reserve = input.pluginCfg.budget.outputReserveTokens
            val extra = SampleAgreement.extraTokens(input.ruleId, input.pluginCfg, input.model, heldOf(input).toLong(), 1, reserve)
            return findings to extra.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val halves = retryHalves(watched, logger) { half -> runHalf(half, skips) }
        if (halves.isNullOrEmpty()) return findings to 0
        return deduplicateChunkFindings(replaceCapped(findings, halves)) to 0
    }

    private suspend fun dispatchRule(
        input: DispatchInput,
        skips: SkipLog,
        budget: AiTokenBudget,
    ): List<Finding> {
        val analyzer = registry.findAiAnalyzer(input.ruleId)
        if (analyzer != null && input.aiService != null) {
            val diffContext = diffContextFor(input, skips)

            val strategy = strategyFor(input)
            val useSemiFormal = strategy is SemiFormalStrategy &&
                analyzer.supportsSemiFormalReasoning

            val semiStrategy = strategy as? SemiFormalStrategy
            val extraInstructions = extraInstructionsFor(input)
            val effectivePrompt = if (useSemiFormal && semiStrategy != null) {
                val certType = semiStrategy.certificateType
                    ?: CertificateType.forAnalyzer(analyzer.analyzerId)
                SemiFormalPromptBuilder.buildPrompt(
                    basePrompt = analyzer.defaultPrompt ?: "",
                    extraInstructions = extraInstructions,
                    systemPromptOverride = input.resolvedPrompt,
                    responseFormatSuffix = analyzer.responseFormatSuffix,
                    certificateType = certType,
                )
            } else {
                null
            }
            val promptSource = when {
                effectivePrompt != null -> "semi-formal"
                input.resolvedPrompt != null -> "configured prompt"
                else -> "analyzer default"
            }
            logger.info(
                "AI plugin [${input.ruleId}]: prompt source=$promptSource, " +
                    "extraInstructions=${extraInstructions?.length ?: 0} chars",
            )

            val structured = structuredOutputFor(input) && !useSemiFormal
            val gatedService = StructuredOutputGate.apply(input.aiService, structured, analyzer.resultSerializer, budget)
            val unavailable = UnavailableFieldsNote.current()
            val contextBlocks = input.contextBlocks + listOfNotNull(UnavailableFieldsNote.block(unavailable))
            val certificateCheck = if (useSemiFormal) CertificateCheck(gatedService) else null

            // mrLevel analyzers (a summary, a score) answer in prose no two models word alike: one model, no vote.
            val consensus = input.pluginCfg.analyzerConsensus && !analyzer.mrLevel
            val analyzerFindings = ConsensusRun.run(input.pluginCfg, consensus, input.model, logger) { model, probe ->
                val usage = UsageMeter()
                analyzer
                    .analyze(
                        AiAnalyzerContext(
                            mr = input.mr,
                            aiService = SharedContextService(
                                usage.watch(probe.watch(certificateCheck ?: gatedService)),
                                contextBlocks,
                                input.pluginCfg.sharedSystemPrompt,
                            ),
                            budget = AnalyzerBudgetView(budget),
                            model = model,
                            config = input.config,
                            extraInstructions = extraInstructions,
                            resolvedPrompt = effectivePrompt ?: input.resolvedPrompt,
                            accumulatedFindings = input.accumulatedFindings,
                            logger = logger,
                            diffContext = diffContext,
                            // "no design doc" reads differently when the plan had one and dropped it for ai.budget
                            onSkip = { reason ->
                                val related = registry.droppedFor(analyzer, input.contextDropped)
                                skips.add(if (related.isEmpty()) reason else "$reason (ai.budget dropped context: ${named(related)})")
                            },
                            contextBlocks = contextBlocks,
                            unavailableFields = unavailable,
                        ),
                    ).map(usage::stamp)
            }

            val replied = dropEmptyReplyNotices(analyzerFindings, input.ruleId, logger)
            val verified = if (certificateCheck != null) {
                val head = groundingMr(input)
                certificateCheck.filter(filterByVerifiedEvidence(replied, head), head.changes, input.ruleId, logger)
            } else {
                replied
            }
            return verified.map { stampProvenance(it, input, promptSource) }
        }

        if (input.ruleId == AiRuleIds.CHECKS) {
            val checks = PreMergeChecksExecutor.Input(
                input.pluginCfg.checks,
                input.pluginCfg.checksTicketCompliance,
                input.mr,
                diffContextFor(input, skips).preparedDiff,
                input.enrichment,
                input.model,
                input.aiRuleConfig.responseLanguage,
            )
            val service = input.aiService?.let {
                SharedContextService(
                    StructuredOutputGate.apply(it, input.pluginCfg.structuredOutput),
                    input.contextBlocks,
                    input.pluginCfg.sharedSystemPrompt,
                )
            }
            return PreMergeChecksExecutor.execute(checks, service, budget, logger)
        }

        val customRule = input.customRules.find { it.id == input.ruleId }
        if (customRule != null) {
            val diff = diffContextFor(input, skips).preparedDiff
            return ConsensusRun.run(input.pluginCfg, enabled = true, input.model, logger) { model, probe ->
                CustomAiRuleExecutor.execute(
                    customRule,
                    input.mr,
                    input.aiService?.let { probe.watch(StructuredOutputGate.apply(it, input.pluginCfg.structuredOutput)) },
                    input.aiProvider,
                    budget,
                    model,
                    diff,
                    input.aiRuleConfig.responseLanguage,
                )
            }
        }

        return listOf(
            FindingFactory.error(
                rule = input.ruleId,
                message = "AI analyzer '${input.ruleId}' is not registered. " +
                    "Add its module to builtin-modules.txt or install it as an external plugin.",
            )
        )
    }

    /** The rule's diff context, built once per [DiffContextKey]; records what the preprocessor and caps leave out. */
    private suspend fun diffContextFor(
        input: DispatchInput,
        skips: SkipLog,
    ): PrecomputedDiffContext {
        val contextQuality = PerRuleContextResolver.resolveForRule(input.ruleId, input.aiRuleConfig.contextQuality)
        val key = DiffContextKey(
            input.mr,
            input.aiRuleConfig,
            contextQuality,
            DiffContextKey.enrichmentOf(input.enrichment),
            input.pluginCfg.reviewMode,
        )
        // limit: one lock serializes builds of distinct keys too; per-key deferreds if parallel builds matter
        val builtDiffContext = diffContextBuild.withLock {
            diffContexts.get(key) ?: buildDiffContext(input, contextQuality).also { diffContexts.put(key, it) }
        }
        val preprocessor = input.aiRuleConfig.diffPreprocessor
        skips.addOmitted(
            DiffContextBuilder.omittedPaths(input.mr, builtDiffContext.preparedDiff, input.pluginCfg.reviewMode, preprocessor)
        )
        skips.addExcluded(DiffContextBuilder.excludedPaths(input.mr, preprocessor))
        val priority = contextQuality.enabled && contextQuality.truncation.strategy == TruncationStrategy.PRIORITY
        val cutOptions = DiffCuts.Options(input.pluginCfg.reviewMode, preprocessor, priority, input.aiRuleConfig.maxDiffCharsPerFile)
        skips.addCuts(DiffCuts.of(input.mr, builtDiffContext.preparedDiff, cutOptions))
        val fullMr = input.fullMr
        val rawDiffContext = if (fullMr != null && fullMr.changes.size != input.mr.changes.size) {
            builtDiffContext.copy(metadata = DiffContextBuilder.mrMetadata(fullMr, reviewedFiles = input.mr.changes.size))
        } else {
            builtDiffContext
        }
        return if (input.contextProcessors.isEmpty()) {
            rawDiffContext
        } else {
            var ctx = rawDiffContext
            for (processor in input.contextProcessors) {
                ctx = processor.process(ctx, input.mr)
            }
            ctx
        }
    }

    /**
     * Deduplicates findings produced across multiple chunks.
     *
     * Two findings are duplicates when they share [Finding.rule], file path, line, message and
     * category (the same chunk overlap reported twice); the highest [Finding.severity] is kept.
     * Findings without a [Location.FileRef] are never collapsed.
     */
    internal fun deduplicateChunkFindings(findings: List<Finding>): List<Finding> {
        if (findings.size <= 1) return findings

        @Serializable
        data class DedupeKey(
            val rule: String,
            val path: String?,
            val line: Int?,
            val message: String,
            val category: String?,
            val unique: Int?,
        )

        val grouped = findings.withIndex().groupBy { (index, f) ->
            val fileRef = f.location as? Location.FileRef
            DedupeKey(
                rule = f.rule,
                path = fileRef?.path,
                line = fileRef?.line,
                message = f.message,
                category = f.metadata[AiFindingMetadataKeys.CATEGORY],
                unique = index.takeIf { fileRef == null },
            )
        }
        return grouped.values.map { group ->
            group.minByOrNull { it.value.severity.ordinal }?.value ?: group.first().value
        }
    }

    private companion object {
        const val DIFF_CONTEXT_ENTRIES = 16
    }
}

/**
 * The MR at head that grounds [input]'s findings: a chunk's findings may cite files another chunk carries, so the
 * whole MR, cut to the rule's files (rule scope, exclude policy, partial review) and without the files the diff
 * preprocessor drops whole (deleted, pure rename, binary), which the model never saw for this rule.
 */
internal fun groundingMr(input: AiRuleDispatcher.DispatchInput): MergeRequest {
    val head = input.fullMr ?: input.mr
    val rulePaths = input.rulePaths ?: input.mr.changes.mapTo(mutableSetOf()) { it.path }
    val preprocessor = input.aiRuleConfig.diffPreprocessor
    return head.copy(
        changes = head.changes.filter { it.path in rulePaths && DiffPreprocessor.exclusionReason(it, preprocessor) == null },
    )
}

/**
 * Keeps findings whose `file:line` the MR at head backs: the path is changed and not deleted, and the line is one
 * the MR added. A file without a parsable diff cannot be line-checked, so only its path is verified.
 */
internal fun filterByVerifiedEvidence(
    findings: List<Finding>,
    head: MergeRequest,
): List<Finding> {
    if (findings.isEmpty()) return findings
    val addedLinesByPath = head.changes
        .filter { it.changeType != ChangeType.DELETED }
        .associate { it.path to HunkParser.extractAddedLineNumbers(it.diffContent) }
    return findings.filter { finding ->
        val location = finding.location as? Location.FileRef ?: return@filter true
        val added = addedLinesByPath[location.path] ?: return@filter false
        val line = location.line ?: return@filter true
        added.isEmpty() || line in added
    }
}

private const val MAX_NAMED_FILES = 20

/** Up to [MAX_NAMED_FILES] of [paths] for a warning, or `none`. */
internal fun named(paths: Collection<String>): String =
    if (paths.isEmpty()) "none" else paths.take(MAX_NAMED_FILES).joinToString(", ") + if (paths.size > MAX_NAMED_FILES) ", …" else ""

/**
 * The [dropped] context entries ("<enricher id>" or "<enricher id> (cut to …)") whose enricher handles an
 * enrichment id [analyzer] reads ([AiAnalyzerModule.supportedEnrichment]).
 */
private fun ModuleRegistry.droppedFor(
    analyzer: AiAnalyzerModule,
    dropped: List<String>,
): List<String> {
    if (dropped.isEmpty() || analyzer.supportedEnrichment.isEmpty()) return emptyList()
    val read = aiContextEnrichers()
        .filter { EnrichmentGlobMatcher.resolve(analyzer.supportedEnrichment, it.handledEnrichmentIds().toSet()).isNotEmpty() }
        .map { it.id }
        .toSet()
    return dropped.filter { it.substringBefore(" (") in read }
}

/** Returns the inline string value for [ContentSource.Inline], or null for other source types. */
private fun ContentSource.inlineValue(): String? = if (this is ContentSource.Inline) value else null

/** An empty model reply is a provider/model hiccup, not a review result: it is logged, never reported as a finding. */
internal fun dropEmptyReplyNotices(
    findings: List<Finding>,
    ruleId: String,
    logger: Logger,
): List<Finding> {
    val (empty, kept) = findings.partition {
        it.metadata[WellKnownMetadataKeys.ERROR] == AiRuleAnalyzerUtils.PARSE_ERROR && it.metadata["rawResponseLength"] == "0"
    }
    if (empty.isNotEmpty()) logger.warn("[ai] rule=$ruleId: model returned an empty reply (${empty.size}x); no finding emitted")
    return kept
}

private val UNWARNED_CUTS = setOf(DiffCuts.HUNK_WINDOW, DiffCuts.PRIORITY, DiffCuts.STUB_CUT)

/** One WARN per cut kind no other line names (running-total, dropped and excluded files are warned already). */
private fun Logger.warnCuts(
    ruleId: String,
    cuts: List<DiffCut>,
) {
    DiffCuts.countsByKind(cuts.filter { it.how in UNWARNED_CUTS }).forEach { (how, count) ->
        val files = cuts.filter { it.how == how }.map { it.file }
        warn("[ai] rule=$ruleId: diff cut ($how): $count files sent short (${named(files)})")
    }
}

private fun Logger.warnCutByTotal(
    ruleId: String,
    reviewed: List<FileChange>,
    totalCap: Int,
) {
    val cut = RuleCoverage.cutByTotal(reviewed.filter { it.diffContent != null }, totalCap)
    if (cut.isEmpty()) return
    warn("[ai] rule=$ruleId: diff total cut at $totalCap characters, ${cut.size} files short (${named(cut)})")
}

/** A chunk over the call (one hunk bigger than a chunk) is skipped and disclosed, never cut to fit. */
private fun Logger.warnOversize(
    ruleId: String,
    chunks: List<DiffChunk>,
    chunkChars: Int,
) {
    val over = chunks.filter { it.totalChars > chunkChars }
    if (over.isEmpty()) return
    val files = over.flatMap { c -> c.changes.map { it.path } }.distinct()
    warn(
        "[ai] rule=$ruleId: ${over.size} of ${chunks.size} chunks over the $chunkChars characters one call holds, " +
            "skipped rather than cut (${files.size} files not reviewed: ${named(files)})",
    )
}

private fun Logger.warnDropped(
    ruleId: String,
    coverage: RuleCoverage,
) {
    if (coverage.excluded.isNotEmpty()) {
        info(
            "[ai] rule=$ruleId: excluded by the diff preprocessor: ${coverage.excluded.size} files (${named(coverage.excluded)})"
        )
    }
    if (coverage.dropped.isEmpty() && coverage.contextDropped.isEmpty()) return
    val line = "[ai] rule=$ruleId: left out to fit the budget: ${coverage.dropped.size} diff files " +
        "(${named(coverage.dropped)}), context: ${named(coverage.contextDropped)}"
    // Unreviewed diff files are a coverage gap worth a warning; trimmed context blocks are routine.
    if (coverage.dropped.isEmpty()) info(line) else warn(line)
}

private fun Logger.warnTimedOut(
    ruleId: String,
    timedOut: Int,
    total: Int,
) {
    if (timedOut == 0) return
    warn("[ai] rule=$ruleId: $timedOut of $total chunks timed out (ai.executionTimeout); kept the finished chunks")
}

private fun Logger.warnSkippedChunks(
    ruleId: String,
    skippedPaths: List<String>,
    skipped: Int,
    total: Int,
) {
    val paths = skippedPaths.distinct()
    warn(
        "[ai] rule=$ruleId: token budget exhausted, skipped $skipped of $total chunks " +
            "(${paths.size} files not reviewed: ${named(paths)})",
    )
}

private const val HEARTBEAT_INTERVAL_SEC = 30L
private const val MILLIS_PER_SEC = 1000L

private suspend fun <T> Logger.withHeartbeat(
    ruleId: String,
    ruleModel: String,
    block: suspend () -> TimedValue<T>,
): TimedValue<T> =
    coroutineScope {
        val heartbeat = launch {
            var elapsedSec = HEARTBEAT_INTERVAL_SEC
            while (true) {
                delay(HEARTBEAT_INTERVAL_SEC * MILLIS_PER_SEC)
                debug(
                    "AI plugin [$ruleId]: still waiting for response " +
                        "(${elapsedSec}s elapsed, model=$ruleModel)"
                )
                elapsedSec += HEARTBEAT_INTERVAL_SEC
            }
        }
        try {
            block()
        } finally {
            heartbeat.cancel()
        }
    }

/** `ai.<analyzer>.structuredOutput` (keys match the rule id in any spelling) over `ai.structuredOutput`. */
private fun structuredOutputFor(input: AiRuleDispatcher.DispatchInput): Boolean =
    input.pluginCfg.structuredOutputs.entries
        .firstOrNull { ModuleIdNormalizer.matches(it.key, input.ruleId) }
        ?.value ?: input.pluginCfg.structuredOutput

/** A resolved prompt already embeds the assembled extras; otherwise send them as-is. */
private fun extraInstructionsFor(input: AiRuleDispatcher.DispatchInput): String? =
    if (input.resolvedPrompt == null) {
        input.extraInstructions ?: input.pluginCfg.extraInstructions?.inlineValue()
    } else {
        input.pluginCfg.extraInstructions?.inlineValue()
    }

// `ai.<analyzer>.reasoning` overrides `ai.reasoning`; keys match the rule id in any spelling.
// `strictness: low` takes the single-shot DirectStrategy over `ai.reasoning`.
private fun strategyFor(input: AiRuleDispatcher.DispatchInput): ReasoningStrategy {
    val ruleReasoning = input.pluginCfg.reasonings.entries
        .firstOrNull { ModuleIdNormalizer.matches(it.key, input.ruleId) }
        ?.value
    val light = ReviewStrictness.fromString(input.pluginCfg.strictness) == ReviewStrictness.LIGHT
    return if (ruleReasoning == null && light) {
        DirectStrategy
    } else {
        ReasoningStrategy.fromConfig(ruleReasoning ?: input.pluginCfg.reasoning)
    }
}

/**
 * Records which provider/model/prompt produced a finding. `aiFallbackUsed` is derived: the
 * response model differs from the rule's requested model and is one of the configured fallbacks.
 */
private fun stampProvenance(
    finding: Finding,
    input: AiRuleDispatcher.DispatchInput,
    promptSource: String,
): Finding {
    val usedModel = finding.metadata[AiFindingMetadataKeys.AI_MODEL]
    val fallbackUsed = usedModel != null && usedModel != input.model && usedModel in input.pluginCfg.modelFallback
    return finding.copy(
        metadata = finding.metadata + mapOf(
            AiFindingMetadataKeys.AI_PROVIDER to input.pluginCfg.provider,
            AiFindingMetadataKeys.AI_PROMPT_SOURCE to promptSource,
            AiFindingMetadataKeys.AI_FALLBACK_USED to fallbackUsed.toString(),
        ),
    )
}
