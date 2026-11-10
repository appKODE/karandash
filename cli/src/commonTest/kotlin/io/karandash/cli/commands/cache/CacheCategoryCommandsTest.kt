package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.testing.test
import io.karandash.cache.CacheConfig
import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSpec
import io.karandash.cache.CacheTier
import io.karandash.cli.errors.ExitCodes
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `cache stats` and `cache clear` show cache categories and the last run's hit and miss counts. */
class CacheCategoryCommandsTest {
    private val dir = TestFiles.createTestTempDirectory("karandash-cache-category")

    private fun indexEntry(ns: String) = TestFiles.writeText(Path(dir, "index", ns, "a.json"), """{"digest": "x"}""")

    private fun stats() = CacheStatsCommand().test(listOf("--cache-dir", dir.toString()))

    @Test
    fun `stats name the category of each namespace`() {
        indexEntry("http-responses")
        indexEntry("downloads")
        indexEntry("custom")

        val output = stats().output

        assertTrue("http-responses (provider-responses) : 1 entries" in output, output)
        assertTrue("downloads (extends, plugins) : 1 entries" in output, output)
        assertTrue("custom : 1 entries" in output, output)
    }

    @Test
    fun `stats show the hits and misses saved by the last run`() =
        runTest {
            val manager = CacheManager(CacheConfig(), dir)
            val cache = manager.getOrCreate("glob-patterns", CacheSpec<String, String>(maxEntries = 4, tier = CacheTier.MEMORY_ONLY))
            cache.put("k", "v")
            cache.get("k")
            cache.get("absent")
            manager.saveRunStats()

            val output = stats().output

            assertTrue("Last run" in output, output)
            assertTrue("glob-patterns : 1 hits, 1 misses, 50% hit rate" in output, output)
        }

    @Test
    fun `stats label the Jira enrichment cache with the jira category`() =
        runTest {
            val manager = CacheManager(CacheConfig(), dir)
            val jira = manager.getStringCache("enrichment:jira", maxEntries = 4)
            jira.getOrPut("ticket") { "body" }
            jira.getOrPut("ticket") { "body" }
            manager.saveRunStats()

            val output = stats().output

            assertTrue("enrichment:jira (jira) : 1 hits, 1 misses, 50% hit rate" in output, output)
        }

    @Test
    fun `clear invalidates every namespace by category and drops the last run stats`() {
        indexEntry("state")
        CacheManager(CacheConfig(), dir).saveRunStats()

        val result = CacheClearCommand().test(listOf("--cache-dir", dir.toString()))

        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue("state (validation-state) : 1 entries" in result.output, result.output)
        assertFalse(SystemFileSystem.exists(Path(dir, CacheManager.RUN_STATS_FILE)))
        assertFalse(SystemFileSystem.exists(Path(dir, "index", "state", "a.json")))
    }
}
