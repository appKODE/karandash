package io.karandash.platform

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class PathUtilsTest {
    @Test
    fun `isAbsolutePath returns true for Unix absolute path`() {
        assertTrue(isAbsolutePath("/home/user/file.txt"))
    }

    @Test
    fun `isAbsolutePath returns true for Windows absolute path backslash`() {
        assertTrue(isAbsolutePath("C:\\Users\\file.txt"))
    }

    @Test
    fun `isAbsolutePath returns true for Windows absolute path forward slash`() {
        assertTrue(isAbsolutePath("C:/Users/file.txt"))
    }

    @Test
    fun `isAbsolutePath returns false for relative path`() {
        assertFalse(isAbsolutePath("relative/path.txt"))
    }

    @Test
    fun `isAbsolutePath returns false for file name only`() {
        assertFalse(isAbsolutePath("file.txt"))
    }

    @Test
    fun `resolveUserPath returns absolute path unchanged`() {
        val base = Path("/base/dir")
        val result = resolveUserPath("/absolute/path.txt", base)
        assertEquals("/absolute/path.txt", result.toString())
    }

    @Test
    fun `resolveUserPath joins relative path with base`() {
        val base = Path("/base/dir")
        val result = resolveUserPath("relative/file.txt", base)
        assertTrue(result.toString().contains("base"))
        assertTrue(result.toString().contains("relative"))
        assertTrue(result.toString().endsWith("file.txt"))
    }

    /** a leading `~` is not expanded to the home directory; it stays a relative path under the base. */
    @Test
    fun `resolveUserPath does not expand a leading tilde`() {
        val base = Path("/base/dir")
        assertFalse(isAbsolutePath("~/secret.txt"))
        assertEquals(Path("/base/dir", "~/secret.txt"), resolveUserPath("~/secret.txt", base))
    }

    @Test
    fun `canRead returns true for existing readable file`() {
        val dir = TestFiles.createTestTempDirectory("canread-test")
        SystemFileSystem.createDirectories(dir)
        val file = Path(dir, "test.txt")
        try {
            SystemFileSystem.sink(file).use {}
            assertTrue(canRead(file))
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `canRead returns false for non-existing path`() {
        assertFalse(canRead(Path("/nonexistent/path/file.txt")))
    }

    @Test
    fun `createTempFileInDir creates file with prefix and suffix`() {
        val dir = TestFiles.createTestTempDirectory("tmpfile-test")
        SystemFileSystem.createDirectories(dir)
        try {
            val file = createTempFileInDir(dir, "prefix-", ".tmp")
            assertTrue(SystemFileSystem.exists(file))
            assertTrue(file.name.startsWith("prefix-"))
            assertTrue(file.name.endsWith(".tmp"))
            SystemFileSystem.delete(file)
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `createTempFileInDir creates multiple unique files`() {
        val dir = TestFiles.createTestTempDirectory("tmpfiles-test")
        SystemFileSystem.createDirectories(dir)
        try {
            val files = (1..3).map { createTempFileInDir(dir, "test-", ".tmp") }
            val names = files.map { it.name }.toSet()
            assertEquals(3, names.size, "All temp files must have unique names")
            files.forEach { SystemFileSystem.delete(it) }
        } finally {
            SystemFileSystem.delete(dir)
        }
    }
}
