package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.testing.test
import io.karandash.cache.cas.CacheIndexEntry
import io.karandash.cache.cas.CacheIndexStore
import io.karandash.cli.errors.ExitCodes
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CacheStatsCommandTest {
    private val dir = TestFiles.createTestTempDirectory("karandash-cache-stats")

    private fun blob(
        digest: String,
        size: Int,
    ) = TestFiles.writeText(Path(dir, "cas", digest.take(2), digest.drop(2)), "x".repeat(size))

    private fun indexEntry(
        ns: String,
        name: String,
        digest: String,
    ) = TestFiles.writeText(Path(dir, "index", ns, name), """{"digest": "$digest", "expiresAt": 0, "createdAt": 0}""")

    private fun stats() = CacheStatsCommand().test(listOf("--cache-dir", dir.toString()))

    @Test
    fun `stats count blobs size namespaces and orphans`() {
        blob(REFERENCED, 2048)
        blob(ORPHAN, 10)
        indexEntry("rules", "a.json", REFERENCED)
        indexEntry("rules", "b.json", "not-a-digest")
        TestFiles.writeText(Path(dir, "index", "rules", "notes.txt"), "ignored")
        TestFiles.writeText(Path(dir, "cas", ".hidden"), "ignored")

        val result = stats()

        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue("Blobs      : 2" in result.output, result.output)
        assertTrue("Total size : 2 KB" in result.output, result.output)
        assertTrue("rules : 2 entries" in result.output, result.output)
        assertTrue("Orphaned blobs : 1" in result.output, result.output)
    }

    @Test
    fun `an entry the cache wrote references its blob and one that does not decode references none`() {
        blob(REFERENCED, 10)
        blob(ORPHAN, 10)
        CacheIndexStore(Path(dir, "index")).write("rules", "a", CacheIndexEntry(digest = REFERENCED, expiresAt = 1, createdAt = 1))
        TestFiles.writeText(Path(dir, "index", "rules", "b.json"), """{"note": "digest": "$ORPHAN"}""")

        val result = stats()

        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue("Orphaned blobs : 1" in result.output, result.output)
    }

    @Test
    fun `stats report bytes for small caches and megabytes for larger ones`() {
        blob(ORPHAN, 10)
        assertTrue("Total size : 10 B" in stats().output)
        blob(REFERENCED, 2 * 1024 * 1024)
        assertTrue("Total size : 2 MB" in stats().output)
    }

    @Test
    fun `an index entry that cannot be read makes stats incomplete`() {
        SystemFileSystem.createDirectories(Path(dir, "index", "rules", "broken.json", "child"))

        val result = stats()

        assertEquals(ExitCodes.EXECUTION_ERROR, result.statusCode, result.output)
        assertTrue("incomplete" in result.stderr, result.stderr)
    }

    @Test
    fun `a namespace that is a file is reported as unreadable`() {
        TestFiles.writeText(Path(dir, "index", "rules"), "not a directory")
        assertEquals(ExitCodes.EXECUTION_ERROR, stats().statusCode)
    }

    private companion object {
        const val REFERENCED = "ab12345678901234567890123456789012345678901234567890123456789012"
        const val ORPHAN = "cd12345678901234567890123456789012345678901234567890123456789012"
    }
}
