package io.karandash.utils

import io.karandash.utils.GlobTranslator.Mode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobTranslatorTest {
    private fun matches(
        glob: String,
        mode: Mode,
        text: String,
    ) = Regex(GlobTranslator.toRegexSource(glob, mode)).matches(text)

    @Test
    fun `path stars stop at slash and double stars cross it`() {
        assertTrue(matches("src/*.kt", Mode.PATH, "src/A.kt"))
        assertFalse(matches("src/*.kt", Mode.PATH, "src/sub/A.kt"))
        assertTrue(matches("**/A.kt", Mode.PATH, "A.kt"))
        assertTrue(matches("**/A.kt", Mode.PATH, "a/b/A.kt"))
        assertTrue(matches("src/**", Mode.PATH, "src"))
        assertTrue(matches("src/**", Mode.PATH, "src/a/b.kt"))
        assertTrue(matches("a/**/z.kt", Mode.PATH, "a/z.kt"))
        assertTrue(matches("a/**/z.kt", Mode.PATH, "a/b/c/z.kt"))
        assertTrue(matches("a**z", Mode.PATH, "a/b/z"))
        assertTrue(matches("f?.kt", Mode.PATH, "fa.kt"))
        assertFalse(matches("f?.kt", Mode.PATH, "f/.kt"))
    }

    @Test
    fun `path mode treats braces brackets and dots as literals`() {
        assertTrue(matches("a{b}[c](d)+.kt", Mode.PATH, "a{b}[c](d)+.kt"))
        assertFalse(matches("a.kt", Mode.PATH, "aXkt"))
        assertTrue(matches("a\\b", Mode.PATH, "a\\b"))
    }

    @Test
    fun `wildcard mode crosses slashes and escapes everything else`() {
        assertTrue(matches("feat/*", Mode.WILDCARD, "feat/a/b"))
        assertTrue(matches("v?.0", Mode.WILDCARD, "v1.0"))
        assertFalse(matches("v?.0", Mode.WILDCARD, "v1x0"))
        assertTrue(matches("a(b)[c]{d}+^\$|\\", Mode.WILDCARD, "a(b)[c]{d}+^\$|\\"))
    }

    @Test
    fun `text mode supports alternation and character classes`() {
        assertTrue(matches("fix{es,ed}", Mode.TEXT, "fixed"))
        assertFalse(matches("fix{es,ed}", Mode.TEXT, "fixer"))
        assertTrue(matches("a{b,c{d,e}}f", Mode.TEXT, "acef"))
        assertTrue(matches("a{b,c{d,e}}f", Mode.TEXT, "abf"))
        assertTrue(matches("v[0-9]", Mode.TEXT, "v7"))
        assertTrue(matches("v[!0-9]", Mode.TEXT, "vx"))
        assertFalse(matches("v[!0-9]", Mode.TEXT, "v7"))
        assertTrue(matches("*.k?", Mode.TEXT, "a/b.kt"))
    }

    @Test
    fun `text mode keeps unclosed braces and brackets literal`() {
        assertTrue(matches("a{b", Mode.TEXT, "a{b"))
        assertTrue(matches("a[]b", Mode.TEXT, "a[]b"))
        assertTrue(matches("a[b", Mode.TEXT, "a[b"))
    }
}
