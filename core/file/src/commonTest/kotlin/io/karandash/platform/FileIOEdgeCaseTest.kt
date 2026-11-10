package io.karandash.platform

import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Edge-case tests for file I/O primitives: empty files, unicode, Windows CRLF,
 * binary-like content, large payloads, deep directory trees, and special filenames.
 * All tests use TestFiles temp directories so they run on every platform without Docker.
 */
class FileIOEdgeCaseTest {
    private fun tempDir(label: String): Path = TestFiles.createTestTempDirectory(label)

    private fun write(
        path: Path,
        content: String
    ) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
    }

    // -------------------------------------------------------------------------
    // Empty files
    // -------------------------------------------------------------------------

    @Test
    fun `readText on empty file returns empty string`() {
        val dir = tempDir("empty-file")
        val file = Path(dir, "empty.txt")
        try {
            write(file, "")
            assertEquals("", file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `exists returns true for empty file`() {
        val dir = tempDir("empty-exists")
        val file = Path(dir, "zero.txt")
        try {
            write(file, "")
            assertTrue(file.exists())
            assertTrue(file.isFile())
            assertFalse(file.isDirectory())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `canRead returns true for empty file`() {
        val dir = tempDir("empty-canread")
        val file = Path(dir, "empty.yaml")
        try {
            write(file, "")
            assertTrue(canRead(file))
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `fileSize is zero for empty file`() {
        val dir = tempDir("empty-size")
        val file = Path(dir, "zero.bin")
        try {
            write(file, "")
            assertEquals(0L, file.fileSize())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Whitespace-only files (common in CI-generated config snippets)
    // -------------------------------------------------------------------------

    @Test
    fun `readText on whitespace-only file returns the whitespace`() {
        val dir = tempDir("whitespace")
        val file = Path(dir, "spaces.txt")
        try {
            write(file, "   \t   ")
            assertEquals("   \t   ", file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `readText on newline-only file returns the newline`() {
        val dir = tempDir("newline-only")
        val file = Path(dir, "lf.txt")
        try {
            write(file, "\n")
            assertEquals("\n", file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Line-ending styles (CI artefacts may have Windows or Unix endings)
    // -------------------------------------------------------------------------

    @Test
    fun `readText preserves Unix LF line endings`() {
        val dir = tempDir("unix-lf")
        val file = Path(dir, "unix.txt")
        val content = "line1\nline2\nline3"
        try {
            write(file, content)
            assertEquals(content, file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `readText preserves Windows CRLF line endings`() {
        val dir = tempDir("windows-crlf")
        val file = Path(dir, "windows.txt")
        val content = "line1\r\nline2\r\nline3"
        try {
            write(file, content)
            assertEquals(content, file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `readText preserves old Mac CR line endings`() {
        val dir = tempDir("mac-cr")
        val file = Path(dir, "oldmac.txt")
        val content = "line1\rline2\rline3"
        try {
            write(file, content)
            assertEquals(content, file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Unicode content (common in international team configs)
    // -------------------------------------------------------------------------

    @Test
    fun `readText handles CJK characters`() {
        val dir = tempDir("cjk")
        val file = Path(dir, "cjk.txt")
        val content = "功能：验证拉取请求标题格式"
        try {
            write(file, content)
            assertEquals(content, file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `readText handles emoji`() {
        val dir = tempDir("emoji")
        val file = Path(dir, "emoji.txt")
        val content = "^(feat|fix|docs): .+ 🎉"
        try {
            write(file, content)
            assertEquals(content, file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `readText handles accented characters`() {
        val dir = tempDir("accented")
        val file = Path(dir, "french.txt")
        val content = "Vérification des règles de commit"
        try {
            write(file, content)
            assertEquals(content, file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `readText handles Arabic RTL text`() {
        val dir = tempDir("arabic")
        val file = Path(dir, "ar.txt")
        val content = "^(ميزة|إصلاح):"
        try {
            write(file, content)
            assertEquals(content, file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Large files (representative of real regex or CODEOWNERS payloads)
    // -------------------------------------------------------------------------

    @Test
    fun `readText handles 100 KB file correctly`() {
        val dir = tempDir("large")
        val file = Path(dir, "big.txt")
        val line = "^(feat|fix|docs|chore|refactor|test|ci|build|style|perf|revert): .{1,72}$\n"
        val content = line.repeat(1500) // ~100 KB
        try {
            write(file, content)
            assertEquals(content, file.readText())
            assertTrue(file.fileSize() > 90_000L)
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `readBytes and readText are consistent for same file`() {
        val dir = tempDir("bytes-text")
        val file = Path(dir, "both.txt")
        val content = "^(feat|fix): [A-Z].+"
        try {
            write(file, content)
            val fromBytes = file.readBytes().decodeToString()
            val fromText = file.readText()
            assertEquals(fromBytes, fromText)
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Multiple trailing newlines (ExternalValueResolver trims these)
    // -------------------------------------------------------------------------

    @Test
    fun `readText returns raw content including trailing newlines`() {
        val dir = tempDir("trailing-nl")
        val file = Path(dir, "trailing.txt")
        val content = "^(feat|fix):\n\n"
        try {
            write(file, content)
            assertEquals(content, file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // writeText / readText round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `writeText followed by readText returns identical content`() {
        val dir = tempDir("roundtrip")
        val file = Path(dir, "config.yaml")
        val content = "rules:\n  title-format:\n    pattern: \"^feat:\"\n"
        try {
            file.writeText(content)
            assertEquals(content, file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `writeText overwrites previous content`() {
        val dir = tempDir("overwrite")
        val file = Path(dir, "version.txt")
        try {
            file.writeText("v1.0.0")
            file.writeText("v2.0.0")
            assertEquals("v2.0.0", file.readText())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // copyTo
    // -------------------------------------------------------------------------

    @Test
    fun `copyTo creates independent copy with identical content`() {
        val dir = tempDir("copy")
        val src = Path(dir, "src.txt")
        val dst = Path(dir, "dst.txt")
        try {
            write(src, "pattern: ^feat:")
            src.copyTo(dst)
            assertEquals(src.readText(), dst.readText())
            // Verify independence - modifying src does not affect dst
            write(src, "changed")
            assertEquals("pattern: ^feat:", dst.readText())
        } finally {
            if (SystemFileSystem.exists(src)) SystemFileSystem.delete(src)
            if (SystemFileSystem.exists(dst)) SystemFileSystem.delete(dst)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `copyTo with overwrite=true replaces destination`() {
        val dir = tempDir("copy-overwrite")
        val src = Path(dir, "new.txt")
        val dst = Path(dir, "old.txt")
        try {
            write(dst, "old content")
            write(src, "new content")
            src.copyTo(dst, overwrite = true)
            assertEquals("new content", dst.readText())
        } finally {
            if (SystemFileSystem.exists(src)) SystemFileSystem.delete(src)
            if (SystemFileSystem.exists(dst)) SystemFileSystem.delete(dst)
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // deleteIfExists
    // -------------------------------------------------------------------------

    @Test
    fun `deleteIfExists returns true and removes file`() {
        val dir = tempDir("delete-exists")
        val file = Path(dir, "to-delete.txt")
        try {
            write(file, "bye")
            assertTrue(deleteIfExists(file))
            assertFalse(file.exists())
        } finally {
            if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `deleteIfExists returns false for non-existent path`() {
        assertFalse(deleteIfExists(Path("/nonexistent/phantom.txt")))
    }

    // -------------------------------------------------------------------------
    // createDirectories
    // -------------------------------------------------------------------------

    @Test
    fun `createDirectories creates nested directory tree`() {
        val ts = Clock.System.now().toEpochMilliseconds()
        val root = TestFiles.createTestTempDirectory("mkdir-test")
        val deep = Path(root, "a", "b", "c", "d")
        try {
            createDirectories(deep)
            assertTrue(deep.exists())
            assertTrue(deep.isDirectory())
        } finally {
            // Clean up from deepest to shallowest
            listOf("a/b/c/d", "a/b/c", "a/b", "a").forEach { rel ->
                val p = Path(root, rel)
                if (SystemFileSystem.exists(p)) SystemFileSystem.delete(p)
            }
            if (SystemFileSystem.exists(root)) SystemFileSystem.delete(root)
        }
    }

    @Test
    fun `createDirectories returns false for already-existing directory`() {
        val dir = tempDir("mkdir-existing")
        try {
            assertFalse(createDirectories(dir))
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // Special filenames (CI artefacts, hidden files, files with dots)
    // -------------------------------------------------------------------------

    @Test
    fun `reads file whose name starts with a dot hidden file`() {
        val dir = tempDir("hidden")
        val file = Path(dir, ".karandash.yaml")
        try {
            write(file, "rules: {}")
            assertEquals("rules: {}", file.readText())
            assertTrue(file.isFile())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `reads file with multiple dots in name`() {
        val dir = tempDir("multi-dot")
        val file = Path(dir, "config.backup.2024.yaml")
        try {
            write(file, "backup: true")
            assertEquals("backup: true", file.readText())
            assertEquals("yaml", file.extension())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `reads file with no extension`() {
        val dir = tempDir("no-ext")
        val file = Path(dir, "Makefile")
        try {
            write(file, "build:\n\tgo build ./...")
            assertTrue(file.isFile())
            assertEquals("", file.extension())
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // listFiles on various structures
    // -------------------------------------------------------------------------

    @Test
    fun `listFiles returns null for a regular file`() {
        val dir = tempDir("listfiles")
        val file = Path(dir, "plain.txt")
        try {
            write(file, "x")
            val result = file.listFiles()
            assertEquals(null, result, "listFiles on a regular file must return null")
        } finally {
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `listFiles returns empty list for empty directory`() {
        val dir = tempDir("listfiles-empty")
        try {
            val result = dir.listFiles()
            assertNotNull(result)
            assertTrue(result.isEmpty())
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `listFiles returns all direct children`() {
        val dir = tempDir("listdir")
        val f1 = Path(dir, "alpha.txt")
        val f2 = Path(dir, "beta.txt")
        val sub = Path(dir, "subdir")
        try {
            write(f1, "a")
            write(f2, "b")
            SystemFileSystem.createDirectories(sub)
            val children = dir.listFiles().orEmpty()
            assertEquals(3, children.size)
            assertTrue(children.any { it.name == "alpha.txt" })
            assertTrue(children.any { it.name == "beta.txt" })
            assertTrue(children.any { it.name == "subdir" })
        } finally {
            SystemFileSystem.delete(f1)
            SystemFileSystem.delete(f2)
            SystemFileSystem.delete(sub)
            SystemFileSystem.delete(dir)
        }
    }

    // -------------------------------------------------------------------------
    // moveFile
    // -------------------------------------------------------------------------

    @Test
    fun `moveFile relocates content and removes source`() {
        val dir = tempDir("move")
        val src = Path(dir, "before.txt")
        val dst = Path(dir, "after.txt")
        try {
            write(src, "moving")
            moveFile(src, dst)
            assertFalse(src.exists())
            assertTrue(dst.exists())
            assertEquals("moving", dst.readText())
        } finally {
            if (SystemFileSystem.exists(src)) SystemFileSystem.delete(src)
            if (SystemFileSystem.exists(dst)) SystemFileSystem.delete(dst)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `createDirectories tolerates a concurrent creator but not other failures`() {
        val dir = tempDir("mkdir-race")
        val target = Path(dir, "sub")
        try {
            val raced = createDirectories(target) {
                SystemFileSystem.createDirectories(it)
                throw IOException("mkdir failed: File exists")
            }
            assertTrue(raced)
            assertFailsWith<IOException> { createDirectories(Path(dir, "other")) { throw IOException("denied") } }
            assertFalse(createDirectories(target))
        } finally {
            SystemFileSystem.delete(target)
            SystemFileSystem.delete(dir)
        }
    }
}
