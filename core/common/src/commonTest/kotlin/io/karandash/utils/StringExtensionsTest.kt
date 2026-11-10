package io.karandash.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringExtensionsTest {
    @Test
    fun `parseCsvList splits and trims`() {
        assertEquals(listOf("feat", "fix", "docs"), "feat, fix, docs".parseCsvList())
    }

    @Test
    fun `parseCsvList filters empty segments`() {
        assertEquals(listOf("feat", "fix"), "feat,,fix,".parseCsvList())
    }

    @Test
    fun `parseCsvList handles single item`() {
        assertEquals(listOf("feat"), "feat".parseCsvList())
    }

    @Test
    fun `parseCsvList returns empty for blank string`() {
        assertEquals(emptyList(), "".parseCsvList())
        assertEquals(emptyList(), " , , ".parseCsvList())
    }

    @Test
    fun `toEscapedAlternation joins with pipe`() {
        val result = listOf("feat", "fix").toEscapedAlternation()
        assertEquals("feat|fix", result)
    }

    @Test
    fun `toEscapedAlternation single item`() {
        assertEquals("feat", listOf("feat").toEscapedAlternation())
    }

    @Test
    fun `toEscapedAlternation escapes regex special chars`() {
        val result = listOf("feature/", "bug.fix", "a(b)|[c]{d}^$*+?\\").toEscapedAlternation()
        assertEquals("feature/|bug\\.fix|a\\(b\\)\\|\\[c\\]\\{d\\}\\^\\$\\*\\+\\?\\\\", result)
        assertTrue(Regex("^($result)$").matches("a(b)|[c]{d}^$*+?\\"))
    }

    @Test
    fun `toEscapedAlternation produces valid regex`() {
        val pattern = listOf("feat", "fix").toEscapedAlternation()
        val regex = Regex("^($pattern)$")
        assertTrue(regex.matches("feat"))
        assertTrue(regex.matches("fix"))
        assertFalse(regex.matches("docs"))
    }

    @Test
    fun `toEscapedAlternation empty list`() {
        assertEquals("", emptyList<String>().toEscapedAlternation())
    }

    @Test
    fun `takeWhole drops a high surrogate left at the cut`() {
        assertEquals("ab", "ab\uD83D\uDE00c".takeWhole(3))
    }

    @Test
    fun `takeWhole keeps a pair that fits and works like take otherwise`() {
        assertEquals("ab\uD83D\uDE00", "ab\uD83D\uDE00c".takeWhole(4))
        assertEquals("abc", "abcdef".takeWhole(3))
        assertEquals("ab", "ab".takeWhole(5))
        assertEquals("", "\uD83D\uDE00".takeWhole(1))
    }
}
