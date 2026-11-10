package io.karandash.cli.commands.validate

import io.karandash.config.pipeline.ChainEntry
import io.karandash.config.pipeline.ConfigChain
import io.karandash.logger.NoOpLogger
import io.karandash.manifest.ConfigSource
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Watch mode follows the whole `extends` chain, not only the root config file. */
class ChainWatchTest {
    private fun entry(url: String) = ChainEntry(ConfigSource(url), depth = 0, mergeStrategy = null, content = "", format = "yaml")

    @Test
    fun `chain files are the local entries and remote entries are skipped`() {
        val chain = ConfigChain(listOf(entry("https://example.com/base.yaml"), entry("/cfg/parent.yaml"), entry("/cfg/child.yaml")))

        assertEquals(listOf("/cfg/parent.yaml", "/cfg/child.yaml"), chain.localFiles())
    }

    @Test
    fun `touching the parent config triggers a rerun and a dropped parent does not`() =
        runBlocking {
            val dir = java.nio.file.Paths
                .get(TestFiles.createTestTempDirectory("karandash-chain-watch").toString())
            val parent = dir.resolve("parent.yaml").apply { writeText("rules: {}") }
            val child = dir.resolve("child.yaml").apply { writeText("extends: parent.yaml") }
            var fired = CountDownLatch(1)
            val job = Job()
            val watch = ChainWatch(CoroutineScope(job + Dispatchers.IO), debounceMs = 50L, logger = NoOpLogger) { fired.countDown() }

            watch.update(setOf(child.toString(), parent.toString()))
            Thread.sleep(SETTLE_MS)
            parent.writeText("rules: { a: {} }")
            assertTrue(fired.await(WAIT_SECONDS, TimeUnit.SECONDS), "touching the parent config did not trigger a rerun")

            fired = CountDownLatch(1)
            watch.update(setOf(child.toString()))
            Thread.sleep(SETTLE_MS)
            parent.writeText("rules: { b: {} }")
            assertFalse(fired.await(QUIET_SECONDS, TimeUnit.SECONDS), "a parent no longer in the chain still triggered a rerun")

            job.cancelAndJoin()
        }

    private companion object {
        const val SETTLE_MS = 500L
        const val WAIT_SECONDS = 15L
        const val QUIET_SECONDS = 4L
    }
}
