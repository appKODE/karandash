package io.karandash.diagnostics

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** bounded record lists, snapshot capture never throws. */
class DiagnosticCollectorBoundsTest {
    private fun tempReport(name: String): Path = Path(TestFiles.createTestTempDirectory("karandash-diag-$name"), "karandash-diag.json")

    private fun readReport(path: Path): DiagnosticReport {
        val text = SystemFileSystem.source(path).buffered().use { it.readString() }
        SystemFileSystem.delete(path)
        return KarandashJson.Default.decodeFromString<DiagnosticReport>(text)
    }

    @Test
    fun `record lists are capped as ring buffers`() =
        runTest {
            val path = tempReport("bounds")
            val collector = DiagnosticCollector(outputPath = path.toString())
            val total = DiagnosticCollector.MAX_RECORDS + 500

            repeat(total) { i ->
                collector.recordHttp("GET", "https://example.test/$i", 200, durationMs = 1)
            }
            collector.stopAndFinalize(totalDurationMs = 1)

            val report = readReport(path)
            assertEquals(DiagnosticCollector.MAX_RECORDS, report.events.size)
            val summary = assertNotNull(report.summary)
            assertEquals(DiagnosticCollector.MAX_RECORDS, summary.httpRequests.size)
            assertEquals("https://example.test/${total - 1}", summary.httpRequests.last().url)
            assertEquals("https://example.test/500", summary.httpRequests.first().url)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `snapshots are capped at MAX_SNAPSHOTS keeping the newest`() =
        runTest {
            val path = tempReport("snapshots")
            var next = 0L
            val collector = DiagnosticCollector(
                outputPath = path.toString(),
                sampleIntervalMs = 1,
                snapshotProvider = { ResourceSnapshot(timestampMs = next++) },
            )
            collector.start(backgroundScope)
            advanceTimeBy(DiagnosticCollector.MAX_SNAPSHOTS + 50L)
            collector.stopAndFinalize(totalDurationMs = 1)

            val report = readReport(path)
            assertEquals(DiagnosticCollector.MAX_SNAPSHOTS, report.snapshots.size)
            assertEquals(DiagnosticCollector.MAX_SNAPSHOTS, assertNotNull(report.summary).snapshotCount)
            assertEquals(next - DiagnosticCollector.MAX_SNAPSHOTS, report.snapshots.first().timestampMs)
        }

    @Test
    fun `rule enrichment and reporter timings are capped`() =
        runTest {
            val path = tempReport("timings")
            val collector = DiagnosticCollector(outputPath = path.toString())
            val total = DiagnosticCollector.MAX_RECORDS + 7

            repeat(total) { i ->
                collector.recordRuleResult("rule-$i", durationMs = 1, fromCache = false, findingCount = 0)
                collector.recordEnrichmentResult("provider-$i", durationMs = 1, fromCache = false, outputIds = emptyList())
                collector.recordReporterResult("format-$i", "out", formatMs = 1, outputMs = 1)
            }
            collector.stopAndFinalize(totalDurationMs = 1)

            val summary = assertNotNull(readReport(path).summary)
            assertEquals(DiagnosticCollector.MAX_RECORDS, summary.ruleTimings.size)
            assertEquals(DiagnosticCollector.MAX_RECORDS, summary.enrichmentTimings.size)
            assertEquals(DiagnosticCollector.MAX_RECORDS, summary.reporterTimings.size)
            assertEquals("rule-7", summary.ruleTimings.first().ruleId)
            assertEquals("provider-7", summary.enrichmentTimings.first().providerId)
            assertEquals("format-${total - 1}", summary.reporterTimings.last().formatId)
        }

    @Test
    fun `a failing snapshot capture does not break the run`() =
        runTest {
            val path = tempReport("failing")
            val warnings = mutableListOf<String>()
            val logger = object : Logger by NoOpLogger {
                override fun warn(message: String) {
                    warnings += message
                }
            }
            val collector = DiagnosticCollector(
                outputPath = path.toString(),
                logger = logger,
                snapshotProvider = { error("capture exploded") },
            )

            collector.recordStageStart("stage")
            collector.recordStageEnd("stage", durationMs = 1)
            collector.stopAndFinalize(totalDurationMs = 1)

            assertTrue(warnings.any { "capture exploded" in it }, warnings.toString())
            assertNotNull(readReport(path).summary)
        }
}
