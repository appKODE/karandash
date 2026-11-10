@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.cli.TestFileHelpers
import io.karandash.config.DiagnosticConfig
import io.karandash.diagnostics.DiagnosticReport
import io.karandash.duration.KarandashDuration
import io.karandash.logger.NoOpLogger
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour of the `diagnostics` keys: where the report goes and how often it is sampled and flushed. */
class DiagnosticsKeyBehaviourTest {
    private fun ms(value: Long) = KarandashDuration.ofMillis(value)

    /**
     * Starts a bridge from [config] and polls the file for up to [waitMs] of real time until a flushed report
     * satisfies [enough]; returns the last report read (null: none flushed yet).
     */
    private suspend fun flushedWhileRunning(
        config: (String) -> DiagnosticConfig,
        waitMs: Long,
        enough: (DiagnosticReport) -> Boolean = { true },
    ): DiagnosticReport? {
        val dir = TestFileHelpers.createTempDir("diag-keys")
        try {
            val out = Path(dir, "diag.json")
            val session = ModuleRegistryImpl().forRun()
            val bridge = assertNotNull(
                PipelineDiagnosticBridge.createIfConfigured(session, null, config(out.toString()), configLoadMs = 0, logger = NoOpLogger),
            )
            val flushed = withContext(Dispatchers.Default) {
                var last: DiagnosticReport? = null
                repeat((waitMs / POLL_MS).toInt()) {
                    delay(POLL_MS)
                    last = readReport(out) ?: last
                    if (last?.let(enough) == true) return@withContext last
                }
                last
            }
            bridge.finalize(totalMs = 0, logger = NoOpLogger)
            return flushed
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    // A flush can be mid-write when polled: an unreadable file counts as not flushed yet.
    private fun readReport(out: Path): DiagnosticReport? =
        if (SystemFileSystem.exists(out)) {
            runCatching { KarandashJson.Default.decodeFromString<DiagnosticReport>(TestFileHelpers.readFile(out)) }.getOrNull()
        } else {
            null
        }

    @Test
    fun `diagnostics output writes the report to that path`() =
        runTest {
            val dir = TestFileHelpers.createTempDir("diag-output")
            try {
                val out = Path(dir, "custom.json")
                val bridge = assertNotNull(
                    PipelineDiagnosticBridge.createIfConfigured(
                        ModuleRegistryImpl().forRun(),
                        null,
                        DiagnosticConfig(output = out.toString()),
                        configLoadMs = 3,
                        logger = NoOpLogger,
                    ),
                )
                bridge.finalize(totalMs = 0, logger = NoOpLogger)
                assertTrue(SystemFileSystem.exists(out))
            } finally {
                TestFileHelpers.deleteTempDir(dir)
            }
        }

    @Test
    fun `diagnostics without output creates no bridge`() =
        runTest {
            val bridge = PipelineDiagnosticBridge.createIfConfigured(
                ModuleRegistryImpl().forRun(),
                null,
                DiagnosticConfig(),
                configLoadMs = 0,
                logger = NoOpLogger,
            )
            assertNull(bridge)
        }

    @Test
    fun `diagnostics flushInterval writes the file while the run is still going`() =
        runTest {
            val flushed = flushedWhileRunning({ DiagnosticConfig(output = it, flushInterval = ms(50)) }, waitMs = WINDOW_MS)
            assertNotNull(flushed)
        }

    @Test
    fun `diagnostics default flushInterval writes nothing before the run ends`() =
        runTest {
            assertFalse(flushedWhileRunning({ DiagnosticConfig(output = it) }, waitMs = WINDOW_MS) != null)
        }

    @Test
    fun `diagnostics interval sets how often resources are sampled`() =
        runTest {
            val fast = assertNotNull(
                flushedWhileRunning(
                    { DiagnosticConfig(output = it, interval = ms(10), flushInterval = ms(50)) },
                    waitMs = 10_000,
                    enough = { it.snapshots.size >= MANY_SNAPSHOTS },
                ),
            )
            assertTrue(fast.snapshots.size >= MANY_SNAPSHOTS, "expected frequent samples, got ${fast.snapshots.size}")
            // The default 500 ms interval cannot reach that count in the same ~1.5 s window.
            val default = assertNotNull(
                flushedWhileRunning({ DiagnosticConfig(output = it, flushInterval = ms(50)) }, waitMs = WINDOW_MS),
            )
            assertTrue(default.snapshots.size < MANY_SNAPSHOTS, "default interval sampled ${default.snapshots.size}")
        }

    private companion object {
        const val POLL_MS = 50L
        const val WINDOW_MS = 1_500L
        const val MANY_SNAPSHOTS = 20
    }
}
