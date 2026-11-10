package io.karandash.helpers

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegexHelperTest {
    @Test
    fun `tryCompile - valid pattern returns regex`() {
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile(".*", "test-rule", findings)
        assertNotNull(result)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `a rejected pattern finding carries the INVALID_REGEX_PATTERN code`() {
        for (pattern in listOf("[invalid", "(a+)+", "a".repeat(SafeRegexCompiler.MAX_PATTERN_LENGTH + 1))) {
            val findings = mutableListOf<Finding>()
            assertNull(RegexHelper.tryCompile(pattern, "test-rule", findings))
            assertEquals(listOf("E0003"), findings.map { it.errorCode }, pattern.take(10))
        }
    }

    @Test
    fun `tryCompile - invalid pattern adds error finding and returns null`() {
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("[invalid", "test-rule", findings)
        assertNull(result)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("test-rule", findings[0].rule)
        assertTrue(findings[0].message.contains("[invalid"))
    }

    @Test
    fun `tryCompile - compiled regex matches expected strings`() {
        val findings = mutableListOf<Finding>()
        val regex = RegexHelper.tryCompile("^feat/.+", "test-rule", findings)
        assertNotNull(regex)
        assertTrue(regex.matches("feat/my-feature"))
        assertTrue(!regex.matches("fix/my-bug"))
    }

    @Test
    fun `tryCompileAll - all valid patterns compile`() {
        val findings = mutableListOf<Finding>()
        val patterns = listOf("^feat/.+", "^fix/.+", "^docs/.+")
        val result = RegexHelper.tryCompileAll(patterns, "test-rule", findings)
        assertEquals(3, result.size)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `tryCompileAll - invalid patterns are dropped and errors collected`() {
        val findings = mutableListOf<Finding>()
        val patterns = listOf("^feat/.+", "[invalid", "^fix/.+")
        val result = RegexHelper.tryCompileAll(patterns, "test-rule", findings)
        assertEquals(2, result.size) // only the 2 valid ones
        assertEquals(1, findings.size) // one error for [invalid
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("test-rule", findings[0].rule)
    }

    @Test
    fun `tryCompileAll - empty list returns empty list with no findings`() {
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompileAll(emptyList(), "test-rule", findings)
        assertTrue(result.isEmpty())
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `tryCompile - error message contains pattern and cause`() {
        val findings = mutableListOf<Finding>()
        RegexHelper.tryCompile("***", "my-rule", findings)
        assertEquals(1, findings.size)
        val msg = findings[0].message
        assertTrue(msg.contains("***"), "Message should contain the bad pattern")
    }

    // ── ReDoS guard tests ──────────────────────────────────────────────

    @Test
    fun `tryCompile - rejects pattern exceeding MAX_PATTERN_LENGTH`() {
        val findings = mutableListOf<Finding>()
        val longPattern = "a".repeat(RegexHelper.MAX_PATTERN_LENGTH + 1)
        val result = RegexHelper.tryCompile(longPattern, "test-rule", findings)
        assertNull(result)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("too long"))
    }

    @Test
    fun `tryCompile - accepts pattern at MAX_PATTERN_LENGTH`() {
        val findings = mutableListOf<Finding>()
        val exactPattern = "a".repeat(RegexHelper.MAX_PATTERN_LENGTH)
        val result = RegexHelper.tryCompile(exactPattern, "test-rule", findings)
        assertNotNull(result)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `tryCompile - rejects nested group quantifiers`() {
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("((a)+)+", "test-rule", findings)
        assertNull(result)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("nested quantifiers"))
    }

    @Test
    fun `tryCompile - rejects nested star group quantifiers`() {
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("((.*)+)*", "test-rule", findings)
        assertNull(result)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("nested quantifiers"))
    }

    @Test
    fun `tryCompile - rejects atom quantifiers inside quantified group`() {
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("(a+)+", "test-rule", findings)
        assertNull(result)
        assertTrue(findings.single().message.contains("nested quantifiers"))
    }

    @Test
    fun `tryCompile - allows MAPS-style pattern with escaped literals and digit quantifier`() {
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("^((\\[MAPS-\\d+\\])+)?(\\[(ios|android|core)\\]) .+", "test-rule", findings)
        assertNotNull(result)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `tryCompile - allows flat quantifiers inside groups`() {
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("(a+)(b+)", "test-rule", findings)
        assertNotNull(result)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `tryCompile - allows quantifier on group without inner quantifier`() {
        val findings = mutableListOf<Finding>()
        val result = RegexHelper.tryCompile("(abc)+", "test-rule", findings)
        assertNotNull(result)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `quantifierNestingDepth - zero for simple patterns`() {
        assertEquals(0, RegexHelper.quantifierNestingDepth("abc"))
        assertEquals(0, RegexHelper.quantifierNestingDepth("^feat/.+"))
    }

    @Test
    fun `quantifierNestingDepth - one for flat quantifier on group`() {
        assertEquals(1, RegexHelper.quantifierNestingDepth("(abc)+"))
        assertEquals(1, RegexHelper.quantifierNestingDepth("(a|b)*"))
    }

    @Test
    fun `quantifierNestingDepth - two for atom quantifiers inside quantified group`() {
        assertEquals(2, RegexHelper.quantifierNestingDepth("(a+)+"))
        assertEquals(2, RegexHelper.quantifierNestingDepth("(a*)*"))
        assertEquals(2, RegexHelper.quantifierNestingDepth("(x+y?)+"))
        assertEquals(1, RegexHelper.quantifierNestingDepth("(\\[MAPS-\\d+\\])+"))
    }

    @Test
    fun `quantifierNestingDepth - two for nested group quantifiers`() {
        assertEquals(2, RegexHelper.quantifierNestingDepth("((a)+)+"))
        assertEquals(2, RegexHelper.quantifierNestingDepth("((x)*)+"))
        assertEquals(2, RegexHelper.quantifierNestingDepth("((.*)+)*"))
    }

    @Test
    fun `quantifierNestingDepth - ignores quantifiers in character classes`() {
        assertEquals(0, RegexHelper.quantifierNestingDepth("[a+]+"))
    }

    @Test
    fun `quantifierNestingDepth - handles escaped characters`() {
        assertEquals(0, RegexHelper.quantifierNestingDepth("\\(a+\\)+"))
    }
}
