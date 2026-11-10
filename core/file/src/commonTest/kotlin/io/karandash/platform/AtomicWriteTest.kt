package io.karandash.platform

import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicWriteTest {
    @Test
    fun `writeTextAtomic replaces the file and leaves no temp file`() {
        val dir = TestFiles.createTestTempDirectory("atomic-write")
        try {
            val file = Path(dir, "state.json")
            file.writeText("old")
            file.writeTextAtomic("new")
            assertEquals("new", file.readText())
            assertEquals(listOf("state.json"), SystemFileSystem.list(dir).map { it.name })
        } finally {
            TestFiles.deleteRecursively(dir)
        }
    }

    @Test
    fun `writeBytesAtomic into a missing directory fails and creates nothing`() {
        val dir = TestFiles.createTestTempDirectory("atomic-write-missing")
        try {
            assertFailsWith<IOException> { Path(dir, "absent", "blob").writeBytesAtomic(byteArrayOf(1)) }
            assertEquals(emptyList(), SystemFileSystem.list(dir).map { it.name })
        } finally {
            TestFiles.deleteRecursively(dir)
        }
    }

    @Test
    fun `a failed cleanup is kept as a suppressed exception of the write failure`() {
        val dir = TestFiles.createTestTempDirectory("atomic-write-cleanup")
        try {
            val seed = 42
            // The temp path is a non-empty directory: writing to it fails, and so does deleting it.
            val temp = Path(dir, ".blob.${Random(seed).nextLong().toULong().toString(16)}.tmp")
            SystemFileSystem.createDirectories(temp)
            Path(temp, "keep").writeText("x")

            val failure = assertFailsWith<IOException> {
                Path(dir, "blob").writeBytesAtomic(byteArrayOf(1), SystemFileSystem, Random(seed))
            }

            assertEquals(1, failure.suppressedExceptions.size)
        } finally {
            TestFiles.deleteRecursively(dir)
        }
    }
}
