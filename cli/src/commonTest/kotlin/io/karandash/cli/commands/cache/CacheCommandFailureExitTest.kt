package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.testing.test
import io.karandash.cache.cas.CasBlobStore
import io.karandash.cli.commands.report.ReportSummaryCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.testfixtures.TestFiles
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SS-2: cache and report commands exit non-zero on (partial) failure instead of printing and exiting 0. */
class CacheCommandFailureExitTest {
    private val dir = TestFiles.createTestTempDirectory("karandash-cache-exit")

    /** A non-empty directory where a blob or the cas/index dir is expected: deleting or listing it fails. */
    private fun blockingEntry(relative: String) {
        SystemFileSystem.createDirectories(Path(dir, relative, "child"))
    }

    /** A regular file where a directory is expected: listing it fails. */
    private fun fileInsteadOfDir(relative: String) {
        TestFiles.writeText(Path(dir, relative), "not a directory")
    }

    @Test
    fun `cache push without backend url is a config error`() {
        assertEquals(ExitCodes.CONFIG_ERROR, cachePushCommand().test(listOf("--cache-dir", dir.toString())).statusCode)
    }

    @Test
    fun `cache push with failed uploads exits non-zero`() {
        CasBlobStore(Path(dir.toString(), "cas")).put("blob".encodeToByteArray())
        val result = cachePushCommand().test(
            listOf("--cache-dir", dir.toString(), "--backend-url", "http://127.0.0.1:1"),
        )
        assertEquals(ExitCodes.EXECUTION_ERROR, result.statusCode, result.output)
    }

    @Test
    fun `cache clear with an undeletable entry exits non-zero`() {
        blockingEntry("cas/ab/cdef")
        assertEquals(ExitCodes.EXECUTION_ERROR, CacheClearCommand().test(listOf("--cache-dir", dir.toString())).statusCode)
    }

    @Test
    fun `cache gc with an unreadable index dir exits non-zero`() {
        fileInsteadOfDir("index")
        assertEquals(ExitCodes.EXECUTION_ERROR, CacheGcCommand().test(listOf("--cache-dir", dir.toString())).statusCode)
    }

    @Test
    fun `cache stats with an unreadable cas dir exits non-zero`() {
        fileInsteadOfDir("cas")
        assertEquals(ExitCodes.EXECUTION_ERROR, CacheStatsCommand().test(listOf("--cache-dir", dir.toString())).statusCode)
    }

    @Test
    fun `cache remote-stats with an unreadable blob dir exits non-zero`() {
        fileInsteadOfDir("cas")
        val result = CacheRemoteStatsCommand().test(listOf("--cache-dir", dir.toString()))
        assertEquals(ExitCodes.EXECUTION_ERROR, result.statusCode)
    }

    @Test
    fun `cache gc removes a blob no index entry references`() {
        SystemFileSystem.createDirectories(Path(dir, "index", "rules"))
        val digest = CasBlobStore(Path(dir.toString(), "cas")).put("orphan".encodeToByteArray())
        val result = CacheGcCommand().test(listOf("--cache-dir", dir.toString()))
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertContains(result.output, "Orphan blobs removed    : 1")
        assertFalse(CasBlobStore(Path(dir.toString(), "cas")).exists(digest))
    }

    @Test
    fun `cache remote-stats counts blobs and notes a configured backend`() {
        CasBlobStore(Path(dir.toString(), "cas")).put("blob".encodeToByteArray())
        SystemFileSystem.createDirectories(Path(dir, "cas", ".hidden"))
        val bare = CacheRemoteStatsCommand().test(listOf("--cache-dir", dir.toString()))
        assertEquals(ExitCodes.SUCCESS, bare.statusCode, bare.output)
        assertContains(bare.output, "Blobs        : 1")
        assertContains(bare.output, "Total size   : 4 B")
        assertContains(bare.output, "No remote backend configured")
        val heads = mutableListOf<String>()
        val engine = MockEngine { request ->
            heads += "${request.method.value} ${request.url}"
            respond(ByteArray(0), HttpStatusCode.NotFound)
        }
        val configured = CacheRemoteStatsCommand(engine = engine).test(
            listOf("--cache-dir", dir.toString(), "--backend-type", "http", "--backend-url", "https://cache.example.com"),
        )
        assertContains(configured.output, "Backend URL  : https://cache.example.com")
        assertContains(configured.output, "Reachable    : yes")
        assertEquals(1, heads.size, heads.toString())
        assertTrue(heads.single().startsWith("HEAD https://cache.example.com/cas/"), heads.toString())
        assertFalse("No remote backend configured" in configured.output)
        val down = CacheRemoteStatsCommand(engine = MockEngine { throw IOException("connection refused") }).test(
            listOf("--cache-dir", dir.toString(), "--backend-url", "https://cache.example.com", "--backend-type", "http"),
        )
        assertContains(down.output, "Reachable    : no (connection refused)")
        val typeOnly = CacheRemoteStatsCommand().test(listOf("--cache-dir", dir.toString(), "--backend-type", "s3"))
        assertContains(typeOnly.output, "No remote backend configured")
    }

    @Test
    fun `cache remote-stats expands a home relative cache dir`() {
        val result = CacheRemoteStatsCommand().test(listOf("--cache-dir", "~/karandash-k10-missing-cache-dir"))
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertContains(result.output, "directory does not exist")
    }

    @Test
    fun `cache commands succeed on an empty cache`() {
        val args = listOf("--cache-dir", dir.toString())
        assertEquals(ExitCodes.SUCCESS, CacheClearCommand().test(args).statusCode)
        assertEquals(ExitCodes.SUCCESS, CacheGcCommand().test(args).statusCode)
        assertEquals(ExitCodes.SUCCESS, CacheStatsCommand().test(args).statusCode)
        assertEquals(ExitCodes.SUCCESS, CacheRemoteStatsCommand().test(args).statusCode)
    }

    @Test
    fun `report summary without findings array is a config error`() {
        val file = Path(dir, "result.json")
        TestFiles.writeText(file, """{"passed": true}""")
        assertEquals(ExitCodes.CONFIG_ERROR, ReportSummaryCommand().test(listOf("--from", file.toString())).statusCode)
    }
}
