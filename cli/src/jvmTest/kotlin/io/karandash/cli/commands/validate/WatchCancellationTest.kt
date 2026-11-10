package io.karandash.cli.commands.validate

import io.karandash.testfixtures.TestFiles
import io.karandash.watcher.ConfigWatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

/** SS-3: cancelling the command stops watch mode instead of leaving the watcher loop blocking forever. */
class WatchCancellationTest {
    @Test
    fun `cancelling the caller stops the blocking watcher`() {
        val file = java.io
            .File(
                TestFiles.createTestTempDirectory("karandash-watch").toString(),
                "watch.yaml"
            ).apply { writeText("rules: {}") }
        val watcher = ConfigWatcher(file.toString(), debounceMs = 50L) {}
        // Separate thread + join timeout: if cancellation stops reaching the watcher, the test fails instead of hanging.
        val worker = thread {
            runBlocking {
                val watching = launch { watcher.watchUntilCancelled() }
                delay(300.milliseconds)
                watching.cancelAndJoin()
            }
        }
        worker.join(JOIN_TIMEOUT_MS)
        val stuck = worker.isAlive
        if (stuck) watcher.stop()
        assertFalse(stuck, "watch mode kept running after its coroutine was cancelled")
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 10_000L
    }
}
