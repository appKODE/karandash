package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitArgSanitizerTest {
    @Test
    fun `normal relative path accepted`() {
        assertEquals("src/main/Auth.kt", GitArgSanitizer.sanitizePath("src/main/Auth.kt"))
    }

    @Test
    fun `path with spaces accepted`() {
        assertEquals("path with spaces/file.kt", GitArgSanitizer.sanitizePath("path with spaces/file.kt"))
    }

    @Test
    fun `windows separators normalised to forward slashes`() {
        assertEquals("src/main/Auth.kt", GitArgSanitizer.sanitizePath("src\\main\\Auth.kt"))
    }

    /** a path starting with `-` would be read by git as an option. */
    @Test
    fun `a path with a leading dash is rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("-rf") }
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("--output=x") }
        assertEquals("a/-b.kt", GitArgSanitizer.sanitizePath("a/-b.kt"))
    }

    @Test
    fun `semicolon rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("file;rm -rf /") }
    }

    @Test
    fun `pipe rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("a|b") }
    }

    @Test
    fun `ampersand rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("a&b") }
    }

    @Test
    fun `newline rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("a\nb") }
    }

    @Test
    fun `carriage return rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("a\rb") }
    }

    @Test
    fun `null byte rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("a\u0000b") }
    }

    @Test
    fun `command substitution rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("foo\$(bar)") }
    }

    @Test
    fun `backtick rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("foo`bar`") }
    }

    @Test
    fun `absolute path rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("/etc/passwd") }
    }

    @Test
    fun `windows absolute path rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("C:\\Windows\\System32") }
    }

    @Test
    fun `path traversal rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("../../etc/passwd") }
    }

    @Test
    fun `path traversal hidden mid path rejected`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("a/../b") }
    }

    @Test
    fun `sanitizeUserArgs passes clean args`() {
        val input = listOf("log", "--since=6m", "--format=%H")
        assertEquals(input, GitArgSanitizer.sanitizeUserArgs(input))
    }

    @Test
    fun `sanitizeUserArgs rejects arg with metacharacter`() {
        assertFailsWith<IllegalArgumentException> {
            GitArgSanitizer.sanitizeUserArgs(listOf("log", "--since=6m; rm"))
        }
    }

    @Test
    fun `validateWithinRepo accepts path inside repo`() {
        assertEquals(
            "src/main/Auth.kt",
            GitArgSanitizer.validateWithinRepo("src/main/Auth.kt", "/tmp/repo"),
        )
    }

    @Test
    fun `validateWithinRepo trims trailing slash on root`() {
        assertEquals(
            "file.kt",
            GitArgSanitizer.validateWithinRepo("file.kt", "/tmp/repo/"),
        )
    }

    @Test
    fun `validateWithinRepo rejects traversal even through sanitize`() {
        assertFailsWith<IllegalArgumentException> {
            GitArgSanitizer.validateWithinRepo("../outside", "/tmp/repo")
        }
    }

    @Test
    fun `validateWithinRepo rejects absolute paths`() {
        assertFailsWith<IllegalArgumentException> {
            GitArgSanitizer.validateWithinRepo("/etc/passwd", "/tmp/repo")
        }
    }

    @Test
    fun `validateWithinRepo with an empty or filesystem root returns the sanitized path`() {
        assertEquals("a/b.kt", GitArgSanitizer.validateWithinRepo("a\\b.kt", ""))
        assertEquals("a.kt", GitArgSanitizer.validateWithinRepo("a.kt", "/"))
    }

    @Test
    fun `windows drive with forward slash is absolute`() {
        assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("C:/x") }
        assertEquals("C:x", GitArgSanitizer.sanitizePath("C:x"))
    }

    @Test
    fun `violation messages escape control characters`() {
        val newline = assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizeUserArgs(listOf("a\u0007;\n")) }
        assertTrue(newline.message.orEmpty().contains("';'"))
        assertTrue(newline.message.orEmpty().endsWith("a?;\\n"))
        val nul = assertFailsWith<IllegalArgumentException> { GitArgSanitizer.sanitizePath("a\u0000b\r") }
        assertTrue(nul.message.orEmpty().contains("'\\r'"))
        assertTrue(nul.message.orEmpty().endsWith("a\\u0000b\\r"))
    }
}
