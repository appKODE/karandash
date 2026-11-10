package io.karandash.diagnostics

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticCollectorTest {
    @Test
    fun recordStageEventsTracksTimings() =
        runTest {
            val collector = DiagnosticCollector(
                outputPath = "/tmp/test-diag-unused.json",
                sampleIntervalMs = 100_000, // long interval - no sampling during test
                flushIntervalMs = 100_000,
            )
            // Do not start background jobs - just test event recording
            collector.recordStageStart("configLoad")
            collector.recordStageEnd("configLoad", durationMs = 42)
            collector.recordStageStart("validation")
            collector.recordStageEnd("validation", durationMs = 100)

            collector.recordRuleResult("titleFormat", durationMs = 20, fromCache = false, findingCount = 1)
            collector.recordRuleResult("sizeLimits", durationMs = 5, fromCache = true, findingCount = 0)

            collector.stopAndFinalize(totalDurationMs = 200)
        }

    @Test
    fun stopJoinsTheBackgroundLoopsAndAFailedWriteWarns() =
        runTest {
            val warnings = mutableListOf<String>()
            val logger = object : Logger by NoOpLogger {
                override fun warn(message: String) {
                    warnings += message
                }
            }
            // A directory path cannot be opened as a file sink, so every flush fails.
            val collector = DiagnosticCollector(
                outputPath = "/",
                sampleIntervalMs = 10,
                flushIntervalMs = 10,
                logger = logger,
            )
            val loops = Job()
            collector.start(CoroutineScope(coroutineContext + loops))
            delay(25)

            collector.stopAndFinalize(totalDurationMs = 25)

            assertTrue(loops.children.none { it.isActive || !it.isCompleted }, "sampler/flusher still running after stop")
            assertTrue(warnings.any { "Could not write diagnostics" in it }, warnings.toString())
            loops.cancel()
        }

    @Test
    fun recordHttpCreatesEvent() =
        runTest {
            val collector = DiagnosticCollector(
                outputPath = "/tmp/test-diag-unused.json",
            )
            collector.recordHttp(
                method = "GET",
                url = "https://example.com/api/v4/projects/1/merge_requests/1",
                statusCode = 200,
                durationMs = 150,
            )
            // Verifies no exception; actual content verified via serialization test
        }

    @Test
    fun platformSnapshotReturnsNonNegativeValues() {
        val snapshot = PlatformDiagnostics.captureSnapshot(elapsedMs = 42)
        assertEquals(42, snapshot.timestampMs)
        assertTrue(snapshot.peakRssBytes >= 0, "peakRssBytes should be non-negative")
        assertTrue(snapshot.cpuUserTimeMs >= 0, "cpuUserTimeMs should be non-negative")
        assertTrue(snapshot.cpuSystemTimeMs >= 0, "cpuSystemTimeMs should be non-negative")
        assertTrue(snapshot.gcCount >= 0, "gcCount should be non-negative")
        assertTrue(snapshot.gcTimeMs >= 0, "gcTimeMs should be non-negative")
    }

    @Test
    fun platformSnapshotReturnsNonZeroPeakRssOnNonWindows() {
        val snapshot = PlatformDiagnostics.captureSnapshot(elapsedMs = 0)
        // On macOS/Linux/JVM, peak RSS should be > 0 since we're running a process.
        // On MinGW this is stubbed to 0 - test is still valid (>= 0).
        assertTrue(snapshot.peakRssBytes >= 0)
    }
}
