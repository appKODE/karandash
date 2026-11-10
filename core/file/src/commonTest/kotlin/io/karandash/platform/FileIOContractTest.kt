package io.karandash.platform

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class FileIOContractTest {
    private val dir = TestFiles
        .createTestTempDirectory("file-io-contract")
        .also { createDirectories(it) }

    @AfterTest
    fun cleanup() {
        dir.listFiles().orEmpty().forEach { deleteIfExists(it) }
        deleteIfExists(dir)
    }

    @Test
    fun `missing paths are neither files nor directories and have no size`() {
        val missing = Path(dir, "missing.txt")

        assertFalse(missing.isFile())
        assertFalse(missing.isDirectory())
        assertNull(missing.listFiles())
        assertEquals(missing, missing.absolutePathOrSelf())
        assertFailsWith<IllegalStateException> { missing.fileSize() }
    }

    @Test
    fun `listFiles lists the entries of a directory`() {
        val file = Path(dir, "listed.txt").also { it.writeText("x") }

        assertEquals(listOf(file.name), dir.listFiles()?.map { it.name })
    }

    @Test
    fun `existing files resolve and report their size`() {
        val file = Path(dir, "a.txt").also { it.writeText("hello") }

        assertEquals(5L, file.fileSize())
        assertTrue(file.absolutePath().isFile())
        assertEquals("hello", file.absolutePath().readText())
        assertTrue(file.lastModifiedMillis() > 0)
        setExecutable(file)
        assertTrue(canRead(file))
    }

    @Test
    fun `copy and move refuse an existing destination unless overwriting`() {
        val source = Path(dir, "src.txt").also { it.writeText("new") }
        val target = Path(dir, "dst.txt").also { it.writeText("old") }

        assertFailsWith<IllegalStateException> { source.copyTo(target) }
        assertEquals("old", target.readText())
        source.copyTo(target, overwrite = true)
        assertEquals("new", target.readText())

        source.writeText("moved")
        assertFailsWith<IllegalStateException> { moveFile(source, target) }
        moveFile(source, target, overwrite = true)
        assertEquals("moved", target.readText())
        assertFalse(source.exists())
    }

    /** The exclusive create refuses an existing (planted) file and leaves it untouched. */
    @Test
    fun `exclusive create refuses an existing file`() {
        val planted = Path(dir, "planted.tmp").also { it.writeText("planted") }
        val fresh = Path(dir, "fresh.tmp")

        assertFalse(createFileExclusive(planted))
        assertEquals("planted", planted.readText())
        assertTrue(createFileExclusive(fresh))
        assertEquals("", fresh.readText())
        assertFalse(createFileExclusive(fresh))
    }

    /** temp file names are random, so pre-creating the next names cannot block or hijack one. */
    @Test
    fun `pre-created next names neither block nor hand out an existing temp file`() {
        val first = createTempFileInDir(dir, "t-", ".tmp")
        val next = first.name
            .removePrefix("t-")
            .removeSuffix(".tmp")
            .toIntOrNull()
            ?.plus(1)
        val planted = next?.let { (it until it + 100).map { n -> Path(dir, "t-$n.tmp").also { p -> p.writeText("planted") } } }.orEmpty()

        val created = createTempFileInDir(dir, "t-", ".tmp")

        assertFalse(created in planted, created.toString())
        assertEquals("", created.readText())
    }

    /** when every random name is taken, creation fails instead of reusing a planted file. */
    @Test
    fun `temp file creation fails once every attempted name is taken`() {
        val constant = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        Path(dir, "t-0.tmp").writeText("planted")

        val error = assertFailsWith<IllegalStateException> { createTempFileInDir(dir, "t-", ".tmp", constant) }

        assertTrue("after 100 attempts" in error.message.orEmpty(), error.message)
        assertEquals("planted", Path(dir, "t-0.tmp").readText())
    }

    @Test
    fun `fallback temp directory is absolute`() {
        assertTrue(isAbsolutePath(fallbackTempDirectoryString()), fallbackTempDirectoryString())
    }

    @Test
    fun `platform directories are absolute`() {
        listOf(currentWorkingDirectory(), userHomeDirectory(), tempDirectory()).forEach { path ->
            assertTrue(isAbsolutePath(path.toString()), path.toString())
        }
    }
}
