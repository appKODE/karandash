package io.karandash.helpers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SafeRegexCompilerLimitsTest {
    @Test
    fun `installed limits apply to every compiler until cleared`() {
        val pattern = "a".repeat(SafeRegexCompiler.MAX_PATTERN_LENGTH + 100)
        assertIs<SafeRegexCompiler.CompileResult.TooLong>(SafeRegexCompiler.Default.compile(pattern))
        val limits = SafeRegexCompiler.Limits(maxPatternLength = 2000, maxQuantifierNesting = 2)
        SafeRegexCompiler.installLimits(limits)
        try {
            assertIs<SafeRegexCompiler.CompileResult.Success>(SafeRegexCompiler.Default.compile(pattern))
            assertIs<SafeRegexCompiler.CompileResult.Success>(SafeRegexCompiler.Default.compile("((a)+)+"))
        } finally {
            SafeRegexCompiler.clearLimits(limits)
        }
        assertIs<SafeRegexCompiler.CompileResult.NestedQuantifiers>(SafeRegexCompiler.Default.compile("((a)+)+"))
    }

    @Test
    fun `over-long patterns are rejected before nesting analysis or compilation`() {
        val max = SafeRegexCompiler.MAX_PATTERN_LENGTH
        val tooLong = "(a+)+" + "b".repeat(max)
        val result = assertIs<SafeRegexCompiler.CompileResult.TooLong>(SafeRegexCompiler.Default.compile(tooLong))
        assertEquals(tooLong.length, result.length)
        assertEquals(max, result.maxLength)
        assertNull(SafeRegexCompiler.Default.compileOrNull("[" + "a".repeat(max)))
        assertIs<SafeRegexCompiler.CompileResult.Success>(SafeRegexCompiler.Default.compile("a".repeat(max)))
    }
}
