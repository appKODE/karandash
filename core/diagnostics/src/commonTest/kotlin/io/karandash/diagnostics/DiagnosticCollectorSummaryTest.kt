package io.karandash.diagnostics

import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class DiagnosticCollectorSummaryTest {
    private val path = Path(TestFiles.createTestTempDirectory("diag-summary"), "diag-summary.json")

    @AfterTest
    fun cleanup() {
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
    }

    private fun readReport(): DiagnosticReport =
        KarandashJson.Default.decodeFromString(
            DiagnosticReport.serializer(),
            SystemFileSystem.source(path).buffered().use { it.readString() },
        )

    @Test
    fun `final report summarizes every recorded kind of event`() =
        runTest {
            val collector = DiagnosticCollector(path.toString())
            val context = PipelineContext(platform = "gitlab", ruleCount = 2)
            val findings = FindingSummaryDiagnostic(total = 1, errors = 1, warnings = 0, infos = 0, passed = false)
            collector.recordContext(context)
            collector.recordFindingSummary(findings)
            collector.recordClientCreate()
            collector.recordStageStart("fetch")
            collector.recordStageEnd("fetch", durationMs = 10)
            collector.recordStageEnd("never-started", durationMs = 1)
            collector.recordStageStart("validate")
            collector.recordHttp("GET", "https://h/ok", 200, durationMs = 5)
            collector.recordHttp("GET", "https://h/missing", 404, durationMs = 30, responseSizeBytes = 9)
            collector.recordHttp("POST", "https://h/down", null, durationMs = 7, error = "timeout")
            collector.recordHttp("POST", "https://h/unknown", null, durationMs = 1)
            collector.recordRuleResult("r1", durationMs = 3, fromCache = true, findingCount = 0)
            collector.recordRuleResult("r2", 4, false, 1, startMs = 1, endMs = 5, errorCount = 1)
            collector.recordEnrichmentResult("blame", durationMs = 2, fromCache = false, outputIds = listOf("blame"))
            collector.recordReporterResult("console", "stdout", formatMs = 1, outputMs = 2)

            collector.stopAndFinalize(totalDurationMs = 100)

            val report = readReport()
            val summary = assertNotNull(report.summary)
            // the context is recorded before any reporter runs: the ids come from what ran (registry lists stayed empty)
            assertEquals(context.copy(reporterIds = listOf("console:stdout"), enrichmentProviderIds = listOf("blame")), report.context)
            assertEquals(100, summary.totalDurationMs)
            assertEquals(1, summary.snapshotCount)
            assertNull(summary.memoryGrowthRateBytesPerSec)
            assertEquals(mapOf("fetch" to 10L, "never-started" to 1L), summary.stageTimings)
            assertEquals(setOf("fetch"), summary.stageResources.keys)
            assertEquals("validate", summary.lastActiveStage)
            assertNotNull(summary.lastActiveStageDurationMs)
            assertEquals(listOf("r1", "r2"), summary.ruleTimings.map { it.ruleId })
            assertEquals(CacheDiagnostic(hits = 1, misses = 1), summary.cache)
            assertEquals(4, summary.httpCount)
            assertEquals(43, summary.httpTotalMs)
            assertEquals(2, summary.httpErrorCount)
            assertEquals("https://h/missing", summary.slowestHttpUrl)
            assertEquals(30, summary.slowestHttpDurationMs)
            assertEquals(listOf("blame"), summary.enrichmentTimings.map { it.providerId })
            assertEquals(listOf("console"), summary.reporterTimings.map { it.formatId })
            assertEquals(findings, summary.findingSummary)
            assertEquals(ClientLifecycleDiagnostic(clientCreates = 1), summary.clientLifecycle)
            val httpMeta = report.events.filter { it.type == "http" }.map { it.metadata }
            assertEquals(listOf(mapOf("method" to "GET", "status" to "200")), httpMeta.take(1))
            assertEquals(mapOf("method" to "POST"), httpMeta.last())
        }

    @Test
    fun `an intermediate flush has no summary and an empty run finalizes with defaults`() =
        runTest {
            val collector = DiagnosticCollector(path.toString())
            collector.recordStageStart("fetch")

            collector.flush()

            val report = readReport()
            assertNull(report.summary)
            assertEquals(listOf("stage.start"), report.events.map { it.type })

            val empty = DiagnosticCollector(path.toString())
            empty.stopAndFinalize(totalDurationMs = 0)
            val summary = assertNotNull(readReport().summary)
            assertNull(summary.lastActiveStage)
            assertNull(summary.slowestHttpUrl)
            assertEquals(0, summary.httpErrorCount)
        }

    @Test
    fun `sampled snapshots feed the peaks of the summary`() =
        runTest {
            val collector = DiagnosticCollector(path.toString(), sampleIntervalMs = 1, flushIntervalMs = 1_000)
            val loops = Job()
            collector.start(CoroutineScope(coroutineContext + loops))
            testScheduler.advanceTimeBy(5)

            collector.stopAndFinalize(totalDurationMs = 5)
            loops.cancel()

            val report = readReport()
            val summary = assertNotNull(report.summary)
            assertEquals(report.snapshots.size, summary.snapshotCount)
            assertEquals(report.snapshots.maxOf { it.peakRssBytes }, summary.peakMemoryBytes)
            assertEquals(report.snapshots.last().gcCount, summary.totalGcCount)
        }
}
