package io.karandash.cli.pipeline

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.CacheManager
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.compat.ConsoleCapture
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `cache.enabled: false` writes nothing under the cache dir; `--no-cache` only skips reads, so the run's state is still saved. */
class CacheDisabledWritesTest {
    private val mr = """
        {"id": "123", "iid": 456, "title": "x", "description": "", "source_branch": "feature/a", "target_branch": "main",
         "author": {"username": "dev", "name": "Dev"}, "state": "opened", "draft": false, "labels": [], "commits": [],
         "changes": [{"path": "src/A.kt", "changeType": "MODIFIED", "additions": 3, "deletions": 1}]}
    """.trimIndent()

    @Test
    fun `cache enabled false writes nothing under the cache dir`() =
        runTest {
            val files = cacheFilesAfterRun(cacheEnabled = false, noCache = false)
            assertEquals(emptyList(), files)
        }

    @Test
    fun `no-cache still saves the run state`() =
        runTest {
            val files = cacheFilesAfterRun(cacheEnabled = true, noCache = true)
            assertTrue(CacheManager.RUN_STATS_FILE in files, "run stats not saved under --no-cache: $files")
        }

    /** Every file name under the cache dir after one `validate` run with an AI block (no provider key). */
    private suspend fun cacheFilesAfterRun(
        cacheEnabled: Boolean,
        noCache: Boolean,
    ): List<String> {
        val dir = TestFiles.createTestTempDirectory("cache-disabled-writes")
        val cacheDir = Path(dir, "cache")
        val configFile = Path(dir, ".karandash", "config.yaml")
        TestFiles.writeText(Path(dir, "mr.json"), mr)
        TestFiles.writeText(
            configFile,
            "ai:\n  provider: openai\n  statistics:\n    dir: \"$dir/stats\"\ncache:\n  enabled: $cacheEnabled\n  dir: \"$cacheDir\"\n" +
                "rules:\n  title-format:\n    pattern: \"^feat:\"\n",
        )
        try {
            val bootstrap = PluginBootstrap(envReader = { null })
            bootstrap.loadPluginsFromDirs(emptyList())
            ConsoleCapture.capture {
                try {
                    val flags = if (noCache) listOf("--no-cache") else emptyList()
                    ValidateCommand(bootstrap).test(
                        listOf("--no-auto-detect", "--config", configFile.toString(), "--mr", Path(dir, "mr.json").toString()) + flags,
                    )
                } finally {
                    bootstrap.shutdown()
                }
            }
            return filesUnder(cacheDir)
        } finally {
            TestFiles.deleteRecursively(dir)
        }
    }

    private fun filesUnder(path: Path): List<String> {
        if (!SystemFileSystem.exists(path)) return emptyList()
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory != true) return listOf(path.name)
        return SystemFileSystem.list(path).flatMap(::filesUnder)
    }
}
