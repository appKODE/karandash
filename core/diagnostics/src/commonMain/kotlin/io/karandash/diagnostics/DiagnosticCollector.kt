package io.karandash.diagnostics

import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Collects runtime diagnostics (memory/CPU snapshots + pipeline events) and
 * periodically flushes them to a JSON file.
 *
 * Two background coroutines run while active:
 * 1. **Sampler** - captures [ResourceSnapshot] every [sampleIntervalMs].
 * 2. **Flusher** - writes current report to [outputPath] every [flushIntervalMs].
 *
 * Periodic flushing lets data survive global-timeout cancellation or
 * CI job kill - the file always contains recent snapshots.
 *
 * @lifecycle per-run - create one per pipeline invocation.
 */
class DiagnosticCollector(
    private val outputPath: String,
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val logger: Logger = NoOpLogger,
    private val snapshotProvider: (Long) -> ResourceSnapshot = { PlatformDiagnostics.captureSnapshot(it) },
) {
    private val mutex = Mutex()
    private val snapshots = ArrayDeque<ResourceSnapshot>()
    private val events = ArrayDeque<DiagnosticEvent>()
    private val stageTimings = mutableMapOf<String, Long>()
    private val ruleTimings = ArrayDeque<RuleDiagnostic>()
    private val httpRequests = ArrayDeque<HttpDiagnostic>()
    private val enrichmentTimings = ArrayDeque<EnrichmentDiagnostic>()
    private val reporterTimings = ArrayDeque<ReporterDiagnostic>()
    private val cacheHits = atomic(0)
    private val cacheMisses = atomic(0)
    private val cacheSource = atomic(CacheDiagnostic.SOURCE_NONE)
    private val cacheFirstRun = atomic(false)
    private var pipelineContext: PipelineContext? = null
    private var findingSummary: FindingSummaryDiagnostic? = null
    private var aiHealth: AiHealthDiagnostic? = null
    private var aiFunnel: Map<String, Int> = emptyMap()
    private var ruleOutcomes: List<RuleOutcomeDiagnostic> = emptyList()
    private var findings: List<FindingDiagnostic> = emptyList()
    private var lastStage: String? = null
    private var feedback: FeedbackDiagnostic? = null

    // Stage stack for freeze detection - preserves active stage across flushes
    private val activeStageStack = mutableListOf<StageEntry>()

    // Stage resource tracking - snapshots at stage boundaries
    private val stageStartSnapshots = mutableMapOf<String, ResourceSnapshot>()
    private val stageResourceDeltas = mutableMapOf<String, StageResourceDelta>()

    // Client lifecycle tracking - atomic for cross-coroutine safety
    private val clientCreates = atomic(0)
    private val clientCloses = atomic(0)

    private val timeSource = TimeSource.Monotonic
    private var startMark = timeSource.markNow()

    private var samplerJob: Job? = null
    private var flusherJob: Job? = null

    /** Launch background sampler and flusher coroutines in [scope]. */
    fun start(scope: CoroutineScope) {
        startMark = timeSource.markNow()
        samplerJob = scope.launch { samplerLoop() }
        flusherJob = scope.launch { flusherLoop() }
    }

    /** Record the start of a named pipeline stage. */
    suspend fun recordStageStart(stage: String) {
        val elapsed = elapsedMs()
        val snapshot = captureOrNull(elapsed)
        mutex.withLock {
            lastStage = stage
            activeStageStack.add(StageEntry(stage, elapsed))
            if (snapshot != null) stageStartSnapshots[stage] = snapshot
            events.addBounded(
                DiagnosticEvent(
                    timestampMs = elapsed,
                    type = TYPE_STAGE_START,
                    name = stage,
                ),
            )
        }
    }

    /** Record the end of a named pipeline stage with its duration. */
    suspend fun recordStageEnd(
        stage: String,
        durationMs: Long,
    ) {
        val elapsed = elapsedMs()
        val endSnapshot = captureOrNull(elapsed)
        mutex.withLock {
            stageTimings[stage] = durationMs
            lastStage = stage
            activeStageStack.removeAll { it.name == stage }

            val startSnapshot = stageStartSnapshots.remove(stage)
            if (startSnapshot != null && endSnapshot != null) {
                stageResourceDeltas[stage] = StageResourceDelta(
                    rssStartBytes = startSnapshot.currentRssBytes,
                    rssEndBytes = endSnapshot.currentRssBytes,
                    rssDeltaBytes = endSnapshot.currentRssBytes - startSnapshot.currentRssBytes,
                    threadDelta = endSnapshot.threadCount - startSnapshot.threadCount,
                    fdDelta = endSnapshot.openFds - startSnapshot.openFds,
                )
            }

            events.addBounded(
                DiagnosticEvent(
                    timestampMs = elapsed,
                    type = TYPE_STAGE_END,
                    name = stage,
                    durationMs = durationMs,
                ),
            )
        }
    }

    /** Record an HTTP request completion. */
    suspend fun recordHttp(
        method: String,
        url: String,
        statusCode: Int?,
        durationMs: Long,
        responseSizeBytes: Long? = null,
        error: String? = null,
    ) {
        val elapsed = elapsedMs()
        val meta = buildMap {
            put("method", method)
            if (statusCode != null) put("status", statusCode.toString())
        }
        val event = DiagnosticEvent(
            timestampMs = elapsed,
            type = TYPE_HTTP,
            name = url,
            durationMs = durationMs,
            metadata = meta,
        )
        val diagnostic = HttpDiagnostic(
            url = url,
            method = method,
            statusCode = statusCode,
            durationMs = durationMs,
            responseSizeBytes = responseSizeBytes,
            error = error,
        )
        mutex.withLock {
            events.addBounded(event)
            httpRequests.addBounded(diagnostic)
        }
    }

    /** Record a rule execution result with start/end timestamps and severity counts. */
    suspend fun recordRuleResult(
        ruleId: String,
        durationMs: Long,
        fromCache: Boolean,
        findingCount: Int,
        startMs: Long = 0,
        endMs: Long = 0,
        errorCount: Int = 0,
        warningCount: Int = 0,
        infoCount: Int = 0,
    ) {
        val diagnostic = RuleDiagnostic(
            ruleId = ruleId,
            durationMs = durationMs,
            startMs = startMs,
            endMs = endMs,
            fromCache = fromCache,
            findingCount = findingCount,
            errorCount = errorCount,
            warningCount = warningCount,
            infoCount = infoCount,
        )
        val startEvent = DiagnosticEvent(timestampMs = startMs, type = TYPE_RULE_START, name = ruleId)
        val endEvent = DiagnosticEvent(timestampMs = endMs, type = TYPE_RULE_END, name = ruleId, durationMs = durationMs)
        mutex.withLock {
            ruleTimings.addBounded(diagnostic)
            events.addBounded(startEvent)
            events.addBounded(endEvent)
        }
        if (fromCache) cacheHits.incrementAndGet() else cacheMisses.incrementAndGet()
    }

    /** Record an enrichment provider execution result. */
    suspend fun recordEnrichmentResult(
        providerId: String,
        durationMs: Long,
        fromCache: Boolean,
        outputIds: List<String>,
    ) {
        val diagnostic = EnrichmentDiagnostic(
            providerId = providerId,
            durationMs = durationMs,
            fromCache = fromCache,
            outputIds = outputIds,
        )
        mutex.withLock { enrichmentTimings.addBounded(diagnostic) }
    }

    /** Record a reporter format+output execution result. */
    suspend fun recordReporterResult(
        formatId: String,
        outputId: String,
        formatMs: Long,
        outputMs: Long,
    ) {
        val diagnostic = ReporterDiagnostic(
            formatId = formatId,
            outputId = outputId,
            formatMs = formatMs,
            outputMs = outputMs,
        )
        mutex.withLock { reporterTimings.addBounded(diagnostic) }
    }

    /** Record the pipeline execution context (platform, config, MR metadata). */
    suspend fun recordContext(context: PipelineContext) {
        mutex.withLock { pipelineContext = context }
    }

    /** Record which cache layer served the run's state (`local`, `remote` or `none`) and whether none existed. */
    fun recordCacheSource(
        source: String,
        firstRun: Boolean = false,
    ) {
        cacheSource.value = source
        cacheFirstRun.value = firstRun
    }

    /** Record the reviewer feedback read back from the platform (the report's `feedback` block). */
    suspend fun recordFeedback(feedback: FeedbackDiagnostic) {
        mutex.withLock { this.feedback = feedback }
    }

    /** Record the overall finding summary (severity breakdown, pass/fail). */
    suspend fun recordFindingSummary(summary: FindingSummaryDiagnostic) {
        mutex.withLock { findingSummary = summary }
    }

    /** Record whether the AI provider answered this run (`summary.ai.health`) and the review funnel (`summary.ai.funnel`). */
    suspend fun recordAiHealth(
        health: AiHealthDiagnostic,
        funnel: Map<String, Int> = emptyMap(),
    ) {
        mutex.withLock {
            aiHealth = health
            aiFunnel = funnel
        }
    }

    /** Record how each rule went and the run's findings (capped at [MAX_RECORDS]). */
    suspend fun recordOutcomes(
        rules: List<RuleOutcomeDiagnostic>,
        findings: List<FindingDiagnostic>,
    ) {
        mutex.withLock {
            ruleOutcomes = rules
            this.findings = findings.take(MAX_RECORDS)
        }
    }

    /** Record an HTTP client creation event. */
    fun recordClientCreate() {
        clientCreates.incrementAndGet()
    }

    /** Record [creates] HTTP client creations and [closes] closes at once, e.g. from a run's client ledger. */
    fun recordClientLifecycle(
        creates: Int,
        closes: Int,
    ) {
        clientCreates.addAndGet(creates)
        clientCloses.addAndGet(closes)
    }

    /** Record an HTTP client close event. */
    fun recordClientClose() {
        clientCloses.incrementAndGet()
    }

    /**
     * Stop background coroutines, build final summary, and write the
     * complete report. Safe to call from a `finally` block.
     */
    suspend fun stopAndFinalize(totalDurationMs: Long) {
        // Joined, so a periodic flush never races the final one on a multi-threaded dispatcher.
        samplerJob?.cancelAndJoin()
        flusherJob?.cancelAndJoin()

        // Capture one final snapshot
        captureSnapshotSafe()

        val summary = mutex.withLock { buildSummary(totalDurationMs) }
        flushReport(summary)
    }

    /** Write current state to the output file (no summary). */
    suspend fun flush() {
        flushReport(summary = null)
    }

    // Both loops end by cancellation at a delay(); it propagates so the job completes as cancelled.
    private suspend fun samplerLoop() {
        while (true) {
            captureSnapshotSafe()
            delay(sampleIntervalMs)
        }
    }

    private suspend fun flusherLoop() {
        // Wait one interval before first flush
        delay(flushIntervalMs)
        while (true) {
            flushReport(summary = null)
            delay(flushIntervalMs)
        }
    }

    private suspend fun captureSnapshotSafe() {
        val snapshot = captureOrNull(elapsedMs()) ?: return
        mutex.withLock { snapshots.addBounded(snapshot, MAX_SNAPSHOTS) }
    }

    // Diagnostics must never break the run: a failing platform probe only costs a snapshot.
    private fun captureOrNull(elapsed: Long): ResourceSnapshot? =
        try {
            snapshotProvider(elapsed)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // boundary: best-effort platform probe
        ) {
            e.rethrowCancellation()
            logger.warn("Could not capture diagnostics snapshot: ${e.message}")
            null
        }

    private fun <T> ArrayDeque<T>.addBounded(
        item: T,
        max: Int = MAX_RECORDS,
    ) {
        if (size >= max) removeFirst()
        add(item)
    }

    /**
     * The context with the reporters and enrichment providers this run actually used: the context is recorded after
     * validation, before any format/output pair has run, so those lists are filled in from what ran, not what is registered.
     */
    private fun PipelineContext.withRanModules(): PipelineContext =
        copy(
            reporterIds = reporterTimings.map { "${it.formatId}:${it.outputId}" }.distinct(),
            enrichmentProviderIds = enrichmentTimings.map { it.providerId }.distinct(),
        )

    private suspend fun flushReport(summary: DiagnosticSummary?) {
        val report = mutex.withLock {
            DiagnosticReport(
                snapshots = snapshots.toList(),
                events = events.toList(),
                summary = summary,
                context = pipelineContext?.withRanModules(),
                rules = ruleOutcomes,
                findings = findings,
                feedback = feedback,
            )
        }
        try {
            DiagnosticWriter.write(outputPath, report)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // boundary: best-effort file write
        ) {
            e.rethrowCancellation()
            logger.warn("Could not write diagnostics to $outputPath: ${e.message}")
        }
    }

    private fun buildSummary(totalDurationMs: Long): DiagnosticSummary {
        val peakMemory = snapshots.maxOfOrNull { it.peakRssBytes } ?: 0
        val peakCurrentRss = snapshots.maxOfOrNull { it.currentRssBytes } ?: 0
        val maxThreads = snapshots.maxOfOrNull { it.threadCount } ?: 0
        val maxFds = snapshots.maxOfOrNull { it.openFds } ?: 0
        val lastSnapshot = snapshots.lastOrNull()

        val currentActiveStage = activeStageStack.lastOrNull()
        val activeStageDurationMs = currentActiveStage?.let { elapsedMs() - it.startMs } ?: lastStage?.let { stageTimings[it] }

        val slowestHttp = httpRequests.maxByOrNull { it.durationMs }
        val httpErrorCount = httpRequests.count { it.error != null || (it.statusCode != null && it.statusCode >= HTTP_ERROR_THRESHOLD) }
        val memGrowthRate = computeMemoryGrowthRate()
        return DiagnosticSummary(
            totalDurationMs = totalDurationMs,
            peakMemoryBytes = peakMemory,
            peakCurrentRssBytes = peakCurrentRss,
            totalCpuUserMs = lastSnapshot?.cpuUserTimeMs ?: 0,
            totalCpuSystemMs = lastSnapshot?.cpuSystemTimeMs ?: 0,
            totalGcCount = lastSnapshot?.gcCount ?: 0,
            totalGcTimeMs = lastSnapshot?.gcTimeMs ?: 0,
            maxThreadCount = maxThreads,
            maxOpenFds = maxFds,
            snapshotCount = snapshots.size,
            stageTimings = stageTimings.toMap(),
            ruleTimings = ruleTimings.toList(),
            cache = CacheDiagnostic(
                hits = cacheHits.value,
                misses = cacheMisses.value,
                source = cacheSource.value,
                firstRun = cacheFirstRun.value,
            ),
            httpRequests = httpRequests.toList(),
            httpTotalMs = httpRequests.sumOf { it.durationMs },
            httpCount = httpRequests.size,
            enrichmentTimings = enrichmentTimings.toList(),
            reporterTimings = reporterTimings.toList(),
            findingSummary = findingSummary,
            lastActiveStage = currentActiveStage?.name ?: lastStage,
            lastActiveStageDurationMs = activeStageDurationMs,
            clientLifecycle = ClientLifecycleDiagnostic(
                clientCreates = clientCreates.value,
                clientCloses = clientCloses.value,
            ),
            stageResources = stageResourceDeltas.toMap(),
            memoryGrowthRateBytesPerSec = memGrowthRate,
            slowestHttpUrl = slowestHttp?.url,
            slowestHttpDurationMs = slowestHttp?.durationMs,
            httpErrorCount = httpErrorCount,
            ai = aiHealth?.let { AiDiagnostic(it, aiFunnel) },
        )
    }

    /**
     * Compute memory growth rate (bytes/sec) using first and last RSS snapshots.
     * Returns null if fewer than 2 snapshots or zero elapsed time.
     */
    private fun computeMemoryGrowthRate(): Long? {
        if (snapshots.size < MIN_SNAPSHOTS_FOR_GROWTH) return null
        val first = snapshots.first()
        val last = snapshots.last()
        val elapsedSec = (last.timestampMs - first.timestampMs) / MS_PER_SECOND_DOUBLE
        if (elapsedSec <= 0.0) return null
        val delta = last.currentRssBytes - first.currentRssBytes
        return (delta / elapsedSec).toLong()
    }

    private fun elapsedMs(): Long = startMark.elapsedNow().inWholeMilliseconds

    companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MS = 500L
        const val DEFAULT_FLUSH_INTERVAL_MS = 5_000L

        /** Cap per record list; the oldest entry is dropped first. */
        const val MAX_RECORDS = 10_000

        /** Cap for resource snapshots (about 16 minutes at the default sample interval). */
        const val MAX_SNAPSHOTS = 2_000

        private const val TYPE_STAGE_START = "stage.start"
        private const val TYPE_STAGE_END = "stage.end"
        private const val TYPE_HTTP = "http"
        private const val TYPE_RULE_START = "rule.start"
        private const val TYPE_RULE_END = "rule.end"

        private const val HTTP_ERROR_THRESHOLD = 400
        private const val MIN_SNAPSHOTS_FOR_GROWTH = 2
        private const val MS_PER_SECOND_DOUBLE = 1000.0
    }
}

/** Entry in the active stage stack with start timestamp. */
private class StageEntry(
    val name: String,
    val startMs: Long,
)
