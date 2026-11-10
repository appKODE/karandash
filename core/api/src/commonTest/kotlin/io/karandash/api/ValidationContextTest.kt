package io.karandash.api

import io.karandash.api.context.ValidationContext
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationContextTest {
    private val findings = listOf(
        Finding(severity = Severity.ERROR, message = "Security issue", rule = "security"),
        Finding(severity = Severity.WARNING, message = "Title too short", rule = "title-format"),
        Finding(severity = Severity.INFO, message = "Info finding", rule = "security"),
    )
    private val context = ValidationContext(priorFindings = findings)

    @Test
    fun `hasFindings returns true for rules with findings`() {
        assertTrue(context.hasFindings("security"))
        assertTrue(context.hasFindings("title-format"))
    }

    @Test
    fun `hasFindings returns false for rules without findings`() {
        assertFalse(context.hasFindings("commit-messages"))
    }

    @Test
    fun `findingsFor returns filtered findings`() {
        assertEquals(2, context.findingsFor("security").size)
        assertEquals(1, context.findingsFor("title-format").size)
        assertEquals(0, context.findingsFor("unknown").size)
    }

    @Test
    fun `findingsFor with severity filters correctly`() {
        // Severity enum: ERROR=0, WARNING=1, INFO=2
        // severity <= minSeverity compares ordinals: ERROR(0) <= WARNING(1) is true, INFO(2) <= WARNING(1) is false
        // security findings: ERROR + INFO; with minSeverity=WARNING only ERROR passes
        val securityWarningPlus = context.findingsFor("security", Severity.WARNING)
        assertEquals(1, securityWarningPlus.size)
        assertEquals(Severity.ERROR, securityWarningPlus[0].severity)
    }

    @Test
    fun `activeRules returns set of rule IDs with findings`() {
        assertEquals(setOf("security", "title-format"), context.activeRules)
    }

    @Test
    fun `empty context has no findings`() {
        val empty = ValidationContext()
        assertFalse(empty.hasFindings("any"))
        assertEquals(emptyList(), empty.findingsFor("any"))
        assertEquals(emptySet(), empty.activeRules)
    }
}
