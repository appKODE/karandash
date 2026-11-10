package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.cas.CasBlobStore
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.errors.ExitCodes
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The documented `cache info` / `cache clear-expired` names, and `cache clear` emptying the store. */
class CacheCommandAliasTest {
    private val dir = TestFileHelpers.createTempDir("cache-alias")
    private val bootstrap = PluginBootstrap(envReader = { null })

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(dir)

    private fun cache() =
        CacheCommand().subcommands(
            CacheGcCommand(bootstrap),
            CacheStatsCommand(bootstrap),
            CacheClearCommand(bootstrap),
        )

    @Test
    fun `cache info runs cache stats`() {
        val result = cache().test(listOf("info", "--cache-dir", dir.toString()))
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue("Local CAS Cache Statistics" in result.stdout, result.stdout)
    }

    @Test
    fun `cache clear-expired runs cache gc`() {
        SystemFileSystem.createDirectories(Path(dir, "index", "downloads"))
        val result = cache().test(listOf("clear-expired", "--cache-dir", dir.toString()))
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue("GC complete:" in result.stdout, result.stdout)
    }

    @Test
    fun `cache clear deletes stored blobs`() {
        val store = CasBlobStore(Path(dir, "cas"))
        val hash = store.put("cached extends config".encodeToByteArray())
        assertTrue(store.exists(hash))

        val result = cache().test(listOf("clear", "--cache-dir", dir.toString()))

        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue("Blobs deleted   : 1" in result.stdout, result.stdout)
        assertFalse(store.exists(hash))
    }
}
