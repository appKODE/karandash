package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Windows commands run through CreateProcessW with CommandLineToArgvW quoting, never through
 * `cmd.exe /s /c`, so `&`, `|`, `%VAR%` and `"` in an argument stay literal.
 */
class WindowsCommandLineTest {
    @Test
    fun `plain arguments are left as they are`() {
        assertEquals("git log -1 C:\\repo\\a.txt", WindowsCommandLine.of(listOf("git", "log", "-1", "C:\\repo\\a.txt")))
    }

    @Test
    fun `shell metacharacters stay inside one argument`() {
        assertEquals("git \"a & calc\" %PATH%|x", WindowsCommandLine.of(listOf("git", "a & calc", "%PATH%|x")))
    }

    @Test
    fun `quotes and backslashes follow CommandLineToArgvW rules`() {
        assertEquals("\"\"", WindowsCommandLine.quote(""))
        assertEquals("\"a\\\"b c\"", WindowsCommandLine.quote("a\"b c"))
        assertEquals("\"a\\\\\\\"b\"", WindowsCommandLine.quote("a\\\"b"))
        assertEquals("\"dir with space\\\\\"", WindowsCommandLine.quote("dir with space\\"))
        assertEquals("a\\\\b", WindowsCommandLine.quote("a\\\\b"))
    }

    @Test
    fun `quoting table follows CommandLineToArgvW backslash rules`() {
        val bs = "\\"
        val q = "\""
        val cases = listOf(
            // n backslashes before a literal quote are written as 2n+1 and the quote; the parser halves them back
            "a$bs$bs${q}b" to "${q}a$bs$bs$bs$bs$bs${q}b$q",
            "$q" to "$q$bs$q$q",
            // trailing backslashes are doubled before the closing quote
            "x y$bs$bs" to "${q}x y$bs$bs$bs$bs$q",
            // backslashes not followed by a quote stay as they are
            "a b${bs}c" to "${q}a b${bs}c$q",
            "" to "$q$q",
            "tab\tx" to "${q}tab\tx$q",
        )
        cases.forEach { (arg, expected) -> assertEquals(expected, WindowsCommandLine.quote(arg), arg) }
    }
}
