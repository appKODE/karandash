package io.karandash.watcher

import io.karandash.testfixtures.TestFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/** editors save by writing a temp file and renaming it over the config. */
class ConfigWatcherAtomicSaveTest {
    @Test
    fun `watcher detects an atomic replace of the config file`() {
        val dir = java.nio.file.Paths
            .get(TestFiles.createTestTempDirectory("config-watcher-atomic").toString())
        val config = Files.createFile(dir.resolve("config.kts"))
        Files.write(config, "// initial".toByteArray())
        val latch = CountDownLatch(1)
        val watcher = ConfigWatcher(config.toString(), debounceMs = 100L) { latch.countDown() }
        val watchThread = thread { watcher.start() }

        var detected = false
        var round = 0
        while (round < ATTEMPTS && !detected) {
            val tmp: Path = Files.write(dir.resolve("config.kts.tmp"), "// v${round++}".toByteArray())
            Files.move(tmp, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            detected = latch.await(1, TimeUnit.SECONDS)
        }

        watcher.stop()
        watchThread.join(3000)
        Files.deleteIfExists(config)
        Files.deleteIfExists(dir)
        assertTrue(detected, "no change reported after atomic replace")
    }

    private companion object {
        const val ATTEMPTS = 10
    }
}
