package io.karandash.watcher

import io.karandash.testfixtures.TestFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigWatcherTest {
    // macOS WatchService uses polling (~2s with HIGH sensitivity), so tests need generous timeouts
    private val watchDetectionTimeoutMs = 10_000L
    private val writeIntervalMs = 1000L
    private val quietWindowMs = 3000L

    /**
     * Rewrites [file] every [writeIntervalMs] until [latch] opens or [watchDetectionTimeoutMs] passes.
     * Replaces a fixed "wait for the watcher to start" sleep: writes made before the watch is
     * registered are simply retried by the next iteration.
     */
    private fun modifyUntil(
        file: Path,
        latch: CountDownLatch,
    ): Boolean {
        var round = 0
        val attempts = watchDetectionTimeoutMs / writeIntervalMs
        while (round < attempts) {
            Files.write(file, "// modified ${round++}".toByteArray())
            if (latch.await(writeIntervalMs, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    @Test
    fun `watcher detects file modification`() {
        val tempDir = java.nio.file.Paths
            .get(TestFiles.createTestTempDirectory("config-watcher-test").toString())
        val tempFile = Files.createFile(tempDir.resolve("config.kts"))
        Files.write(tempFile, "// initial".toByteArray())

        val latch = CountDownLatch(1)

        val watcher = ConfigWatcher(tempFile.toString(), debounceMs = 200L) {
            latch.countDown()
        }

        val watchThread = thread { watcher.start() }

        val detected = modifyUntil(tempFile, latch)

        watcher.stop()
        watchThread.join(3000)

        assertTrue(detected, "onChange callback should have been triggered after file modification")

        // Cleanup
        Files.deleteIfExists(tempFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `watcher debounces rapid changes`() {
        val tempDir = java.nio.file.Paths
            .get(TestFiles.createTestTempDirectory("config-watcher-debounce-test").toString())
        val tempFile = Files.createFile(tempDir.resolve("config.kts"))
        Files.write(tempFile, "// initial".toByteArray())

        val warmedUp = AtomicBoolean(false)
        val warmUp = CountDownLatch(1)
        val triggerCount = AtomicInteger(0)
        val firstTrigger = CountDownLatch(1)

        val watcher = ConfigWatcher(tempFile.toString(), debounceMs = 300L) {
            if (warmedUp.get()) {
                triggerCount.incrementAndGet()
                firstTrigger.countDown()
            } else {
                warmUp.countDown()
            }
        }

        val watchThread = thread { watcher.start() }

        // Proves the watch is registered before the measured burst starts
        assertTrue(modifyUntil(tempFile, warmUp), "watcher never reported the warm-up change")
        warmedUp.set(true)

        // Rapid modifications
        for (i in 1..5) {
            Files.write(tempFile, "// change $i".toByteArray())
        }

        assertTrue(firstTrigger.await(watchDetectionTimeoutMs, TimeUnit.MILLISECONDS), "burst never triggered")
        // Quiet window for any additional triggers to arrive (never opens, so it waits the full window)
        CountDownLatch(1).await(quietWindowMs, TimeUnit.MILLISECONDS)

        watcher.stop()
        watchThread.join(3000)

        val count = triggerCount.get()
        // Debouncing should coalesce rapid changes - we allow some variance due to OS-level batching
        assertTrue(count in 1..3, "Debounce should coalesce rapid changes, got $count triggers")

        // Cleanup
        Files.deleteIfExists(tempFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `watcher ignores changes to other files`() {
        val tempDir = java.nio.file.Paths
            .get(TestFiles.createTestTempDirectory("config-watcher-ignore-test").toString())
        val watchedFile = Files.createFile(tempDir.resolve("config.kts"))
        val otherFile = Files.createFile(tempDir.resolve("other.txt"))
        Files.write(watchedFile, "// initial".toByteArray())

        val triggerCount = AtomicInteger(0)
        val triggered = CountDownLatch(1)

        val watcher = ConfigWatcher(watchedFile.toString(), debounceMs = 200L) {
            triggerCount.incrementAndGet()
            triggered.countDown()
        }

        val watchThread = thread { watcher.start() }

        // Modify only the other file; a WatchService poll cycle must pass without a callback
        val spurious = modifyUntil(otherFile, triggered)

        watcher.stop()
        watchThread.join(3000)

        assertFalse(spurious, "onChange should not trigger for unrelated file changes")
        assertTrue(triggerCount.get() == 0, "onChange should not trigger for unrelated file changes")

        // Cleanup
        Files.deleteIfExists(otherFile)
        Files.deleteIfExists(watchedFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `watcher stop terminates cleanly`() {
        val tempDir = java.nio.file.Paths
            .get(TestFiles.createTestTempDirectory("config-watcher-stop-test").toString())
        val tempFile = Files.createFile(tempDir.resolve("config.kts"))
        Files.write(tempFile, "// initial".toByteArray())

        val started = CountDownLatch(1)
        val watcher = ConfigWatcher(tempFile.toString(), debounceMs = 200L) { started.countDown() }

        val watchThread = thread { watcher.start() }

        // The first reported change proves start() reached its poll loop
        assertTrue(modifyUntil(tempFile, started), "watcher never started")

        watcher.stop()
        watchThread.join(3000)

        assertTrue(!watchThread.isAlive, "Watch thread should terminate after stop()")

        // Cleanup
        Files.deleteIfExists(tempFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `watcher accepts a bare relative file name`() {
        val failure = java.util.concurrent.atomic
            .AtomicReference<Throwable?>(null)
        val watcher = ConfigWatcher("karandash-watch-relative.yaml", debounceMs = 200L) {}
        val watchThread = thread {
            try {
                watcher.start()
            } catch (e: IllegalArgumentException) {
                failure.set(e)
            }
        }
        watchThread.join(1500)
        watcher.stop()
        watchThread.join(3000)

        assertTrue(failure.get() == null, "start() failed on a relative path: ${failure.get()?.message}")
        assertFalse(watchThread.isAlive, "Watch thread should terminate after stop()")
    }
}
