package io.karandash.helpers

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** atom quantifiers inside a repeated group were not counted as nesting. */
class SafeRegexCompilerNestingTest {
    private val compiler = SafeRegexCompiler.Default

    @Test
    fun `atom quantifier inside repeated group is rejected`() {
        listOf("(a+)+", "(\\w+\\s?)+", "(.*)+", "(a*)*", "(x+y?)+", "(a{2,})+", "(a+){2,}", "(?:a+|b)+").forEach {
            assertIs<SafeRegexCompiler.CompileResult.NestedQuantifiers>(compiler.compile(it), it)
        }
    }

    @Test
    fun `bounded brace quantifier above one counts as repetition`() {
        listOf("(a+){1,100}", "(a{1,100})+", "(a+){50}", "(a{2,5}){2,5}").forEach {
            assertIs<SafeRegexCompiler.CompileResult.NestedQuantifiers>(compiler.compile(it), it)
        }
        listOf("(a+){1}", "(a+){0,1}", "(\\d+\\.){3}\\d+").forEach {
            assertIs<SafeRegexCompiler.CompileResult.Success>(compiler.compile(it), it)
        }
    }

    @Test
    fun `repeated group with a literal the inner atom cannot match stays valid`() {
        listOf(
            "(?:\\[KEY-\\d+\\])+",
            "^(?:\\[KEY-\\d+\\])+ .+$",
            "(?:, \\[KEY-\\d+\\] .+)*",
            "^((\\[MAPS-\\d+\\])+)?(\\[(ios|android|core)\\]) .+",
            "(\\d+\\.)+",
            "(a+b)+",
            "(abc)+",
            "(a+)(b+)",
        ).forEach {
            assertIs<SafeRegexCompiler.CompileResult.Success>(compiler.compile(it), it)
        }
    }

    @Test
    fun `dot plus inside starred group with only matchable literals is ambiguous`() {
        assertTrue(compiler.hasAmbiguousRepetition("(?:, \\[KEY-\\d+\\] .+)*"))
        assertTrue(compiler.hasAmbiguousRepetition("(a+a)+"))
        assertFalse(compiler.hasAmbiguousRepetition("(?:\\[KEY-\\d+\\])+"))
        assertFalse(compiler.hasAmbiguousRepetition("^feat/.+"))
    }

    @Test
    fun `ambiguous repetition compiles with a warning finding`() {
        val findings = mutableListOf<Finding>()
        val regex = RegexHelper.tryCompile("^\\[KEY-\\d+\\] .+(?:, \\[KEY-\\d+\\] .+)*$", "changelog", findings)
        assertNotNull(regex)
        assertEquals(listOf(Severity.WARNING), findings.map { it.severity })
        assertTrue(findings.single().message.contains("backtracking"))
    }

    @Test
    fun `unambiguous repetition compiles without findings`() {
        val findings = mutableListOf<Finding>()
        assertNotNull(RegexHelper.tryCompile("^(?:\\[KEY-\\d+\\])+ .+", "title", findings))
        assertTrue(findings.isEmpty())
    }
}
