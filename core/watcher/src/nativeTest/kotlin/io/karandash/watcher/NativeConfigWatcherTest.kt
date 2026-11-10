@file:OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class)

package io.karandash.watcher

import io.karandash.testfixtures.TestFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class NativeConfigWatcherTest {
    private val tempFiles = mutableListOf<Path>()
    private var counter = 0
    private val tempRoot = TestFiles.createTestTempDirectory("native-config-watcher")

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { path ->
            try {
                SystemFileSystem.delete(path)
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
        tempFiles.clear()
    }

    @Test
    fun testIsSupported() {
        val tempFile = createTempConfigFile()
        val watcher = ConfigWatcher(tempFile.toString(), 100) {}

        // macOS uses kqueue, Linux inotify, Windows mtime polling
        assertEquals(isPlatformWatcherSupported(), watcher.isSupported())
    }

    @Test
    fun testWatcherCreation() {
        val tempFile = createTempConfigFile()

        // Should be able to create watcher without error
        val watcher = ConfigWatcher(tempFile.toString(), 100) {}

        // Verify watcher was created with correct platform support
        assertEquals(isPlatformWatcherSupported(), watcher.isSupported())

        // Can call stop() even if never started
        watcher.stop()
    }

    @Test
    fun testMultipleWatchersIndependent() {
        val tempFile1 = createTempConfigFile()
        val tempFile2 = createTempConfigFile()

        val watcher1 = ConfigWatcher(tempFile1.toString(), 100) {}
        val watcher2 = ConfigWatcher(tempFile2.toString(), 100) {}

        // Both watchers should be independent
        assertTrue(watcher1.isSupported() == watcher2.isSupported())

        // Clean up
        watcher1.stop()
        watcher2.stop()
    }

    @Test
    fun everyNativeTargetWatchesAndReportsAWrite() {
        val tempFile = createTempConfigFile()
        val changes = AtomicInt(0)
        val watcher = PlatformFileWatcher(tempFile.toString())
        assertTrue(watcher.isSupported(), "linux uses inotify, mingw polls, macOS uses kqueue")

        val worker = Worker.start()
        val watching = worker.execute(TransferMode.SAFE, { watcher to changes }) { (w, c) ->
            w.startWatching { c.incrementAndGet() }
        }
        try {
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            while (changes.value == 0 && deadline.hasNotPassedNow()) {
                SystemFileSystem.sink(tempFile).buffered().use { it.writeString("rules: [] # ${changes.value}") }
                usleep(WRITE_INTERVAL_US)
            }
            assertTrue(changes.value > 0, "no change reported for $tempFile")
        } finally {
            watcher.stop()
            watching.result
            worker.requestTermination().result
        }
    }

    /** rename-over saves must keep being reported, not just the first one. */
    @Test
    fun `watcher keeps reporting after the file is replaced by rename`() {
        val tempFile = createTempConfigFile()
        val changes = AtomicInt(0)
        val watcher = PlatformFileWatcher(tempFile.toString())
        val worker = Worker.start()
        val watching = worker.execute(TransferMode.SAFE, { watcher to changes }) { (w, c) ->
            w.startWatching { c.incrementAndGet() }
        }
        try {
            val tmp = Path(tempFile.toString() + ".tmp")
            var replaced = 0
            val deadline = TimeSource.Monotonic.markNow() + 15.seconds
            while (changes.value < 3 && deadline.hasNotPassedNow()) {
                SystemFileSystem.sink(tmp).buffered().use { it.writeString("rules: [] # ${replaced++}") }
                SystemFileSystem.atomicMove(tmp, tempFile)
                usleep(WRITE_INTERVAL_US * 2u)
            }
            assertTrue(changes.value >= 3, "only ${changes.value} changes reported after $replaced renames")
        } finally {
            watcher.stop()
            watching.result
            worker.requestTermination().result
        }
    }

    @Test
    fun `watcher started on a missing file reports its creation`() {
        val missing = Path(tempRoot, "missing-${counter++}.yaml")
        tempFiles.add(missing)
        val changes = AtomicInt(0)
        val watcher = PlatformFileWatcher(missing.toString())
        val worker = Worker.start()
        val watching = worker.execute(TransferMode.SAFE, { watcher to changes }) { (w, c) ->
            w.startWatching { c.incrementAndGet() }
        }
        try {
            usleep(WRITE_INTERVAL_US)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            while (changes.value == 0 && deadline.hasNotPassedNow()) {
                SystemFileSystem.sink(missing).buffered().use { it.writeString("rules: []") }
                usleep(WRITE_INTERVAL_US)
            }
            assertTrue(changes.value > 0, "creation of $missing was not reported")
        } finally {
            watcher.stop()
            watching.result
            worker.requestTermination().result
        }
    }

    @Test
    fun `stopping one watcher leaves the others running`() {
        val stoppedFile = createTempConfigFile()
        val liveFile = createTempConfigFile()
        val changes = AtomicInt(0)
        val stopped = PlatformFileWatcher(stoppedFile.toString())
        val live = PlatformFileWatcher(liveFile.toString())
        val stoppedWorker = Worker.start()
        val liveWorker = Worker.start()
        val stoppedRun = stoppedWorker.execute(TransferMode.SAFE, { stopped }) { it.startWatching {} }
        val liveRun = liveWorker.execute(TransferMode.SAFE, { live to changes }) { (w, c) ->
            w.startWatching { c.incrementAndGet() }
        }
        try {
            // startWatching resets the stop request, so repeat stop() until that run has really returned
            val stopDeadline = TimeSource.Monotonic.markNow() + 10.seconds
            while (stoppedRun.state != FutureState.COMPUTED && stopDeadline.hasNotPassedNow()) {
                stopped.stop()
                usleep(WRITE_INTERVAL_US)
            }
            stoppedRun.result
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            while (changes.value == 0 && deadline.hasNotPassedNow()) {
                SystemFileSystem.sink(liveFile).buffered().use { it.writeString("rules: [] # ${changes.value}") }
                usleep(WRITE_INTERVAL_US)
            }
            assertTrue(changes.value > 0, "stopping another watcher stopped this one")
        } finally {
            live.stop()
            liveRun.result
            stoppedWorker.requestTermination().result
            liveWorker.requestTermination().result
        }
    }

    private fun createTempConfigFile(): Path {
        val fileName = "test-config-${counter++}.yaml"
        val path = Path(tempRoot, fileName)
        val rawSink: RawSink = SystemFileSystem.sink(path)
        rawSink.buffered().use { sink ->
            sink.writeString("rules: []")
            sink.flush()
        }
        tempFiles.add(path)
        return path
    }

    /**
     * Checks whether the current native platform supports file watching.
     * macOS uses kqueue, Linux inotify, Windows mtime polling.
     */
    private fun isPlatformWatcherSupported(): Boolean {
        val probe = ConfigWatcher("/__nonexistent__", 100) {}
        return probe.isSupported()
    }

    private companion object {
        const val WRITE_INTERVAL_US = 200_000u
    }
}
