package io.karandash.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlobExpanderTest {
    @Test
    fun `no braces returns single pattern`() {
        assertEquals(listOf("src/main/*.kt"), GlobExpander.expandBraces("src/main/*.kt"))
    }

    @Test
    fun `two alternatives expand to two patterns`() {
        assertEquals(listOf("a", "b"), GlobExpander.expandBraces("{a,b}"))
    }

    @Test
    fun `three alternatives expand to three patterns`() {
        assertEquals(listOf("a", "b", "c"), GlobExpander.expandBraces("{a,b,c}"))
    }

    @Test
    fun `prefix and suffix are preserved`() {
        assertEquals(listOf("src/a.kt", "src/b.kt"), GlobExpander.expandBraces("src/{a,b}.kt"))
    }

    @Test
    fun `nested braces are flattened`() {
        assertEquals(listOf("a", "b", "c"), GlobExpander.expandBraces("{a,{b,c}}"))
    }

    @Test
    fun `multiple groups produce cross product`() {
        assertEquals(
            listOf("a/c", "a/d", "b/c", "b/d"),
            GlobExpander.expandBraces("{a,b}/{c,d}"),
        )
    }

    @Test
    fun `empty alternative is preserved`() {
        assertEquals(listOf("a", ""), GlobExpander.expandBraces("{a,}"))
    }

    @Test
    fun `single alternative in braces returns that alternative`() {
        assertEquals(listOf("a"), GlobExpander.expandBraces("{a}"))
    }

    @Test
    fun `glob wildcards pass through unchanged`() {
        assertEquals(
            listOf("src/map/**/*.kt", "src/search/**/*.kt"),
            GlobExpander.expandBraces("src/{map,search}/**/*.kt"),
        )
    }

    @Test
    fun `three groups produce correct cross product`() {
        val result = GlobExpander.expandBraces("{a,b}/{c,d}/{e,f}")
        assertEquals(8, result.size)
        assertEquals("a/c/e", result[0])
        assertEquals("a/c/f", result[1])
        assertEquals("a/d/e", result[2])
        assertEquals("a/d/f", result[3])
        assertEquals("b/c/e", result[4])
        assertEquals("b/c/f", result[5])
        assertEquals("b/d/e", result[6])
        assertEquals("b/d/f", result[7])
    }

    @Test
    fun `nested braces with prefix and suffix`() {
        assertEquals(
            listOf("x-a-z", "x-b-z", "x-c-z"),
            GlobExpander.expandBraces("x-{a,{b,c}}-z"),
        )
    }

    @Test
    fun `empty pattern returns single empty string`() {
        assertEquals(listOf(""), GlobExpander.expandBraces(""))
    }

    @Test
    fun `plain glob star does not interfere`() {
        assertEquals(
            listOf("**/*.kt", "**/*.java"),
            GlobExpander.expandBraces("**/*.{kt,java}"),
        )
    }

    @Test
    fun `thirty brace pairs fail fast instead of materializing 2 to the 30 patterns`() {
        val pattern = "{a,b}".repeat(30)
        val error = assertFailsWith<IllegalArgumentException> { GlobExpander.expandBraces(pattern) }
        assertTrue(error.message.orEmpty().contains("more than ${GlobExpander.DEFAULT_MAX_EXPANSION} patterns"))
    }

    @Test
    fun `expansion exactly at the limit succeeds and one over fails`() {
        assertEquals(4, GlobExpander.expandBraces("{a,b}/{c,d}", maxExpansion = 4).size)
        assertFailsWith<IllegalArgumentException> { GlobExpander.expandBraces("{a,b}/{c,d}/{e,f}", maxExpansion = 7) }
    }

    @Test
    fun `nested alternatives count toward the limit`() {
        assertFailsWith<IllegalArgumentException> { GlobExpander.expandBraces("{a,{b,c}}", maxExpansion = 2) }
    }
}
