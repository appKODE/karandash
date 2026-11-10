package io.karandash.sdk.regex

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeRegexTest {
    @Test
    fun safePatternCompiles() {
        val findings = mutableListOf<Finding>()
        assertNotNull(SafeRegex.tryCompile("^feat: .+", "rule", findings))
        assertTrue(findings.isEmpty())
        assertNotNull(SafeRegex.compileOrNull("^\\[\\w+\\]"))
    }

    @Test
    fun catastrophicPatternIsRejectedWithAFinding() {
        val findings = mutableListOf<Finding>()
        assertNull(SafeRegex.tryCompile("((a)+)+", "rule", findings))
        assertEquals(listOf(Severity.ERROR), findings.map { it.severity })
        assertEquals("rule", findings.single().rule)
        assertNull(SafeRegex.compileOrNull("((a)+)+"))
    }

    @Test
    fun invalidPatternIsRejected() {
        assertNull(SafeRegex.compileOrNull("[unclosed"))
    }
}
