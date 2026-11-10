package io.karandash.scripts

import io.karandash.duration.KarandashDuration
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class SandboxScriptExecutorTest {
    @Test
    fun infiniteLoopIsInterruptedAtTimeoutAndKeepsEarlierFindings() =
        runTest {
            val context = TestPluginContext()
            val entry = ScriptEntryConfig(path = "loop.js", timeout = KarandashDuration.ofMillis(200))
            val started = TimeSource.Monotonic.markNow()

            val findings =
                SandboxScriptExecutor(context).run(
                    entry,
                    TestFixtures.minimalMr(),
                    "karandash.warn('before loop');\nwhile (true) {}",
                )

            assertTrue(started.elapsedNow() < 10.seconds, "loop was not interrupted")
            assertEquals(1, findings.size, "warnings=${context.warnings} logs=${context.logs}")
            assertTrue(context.warnings.single().contains("timed out after 200ms"), context.warnings.toString())
        }
}
