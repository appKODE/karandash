package io.karandash.models.finding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindingExtensionsTest {
    private fun makeFinding(
        rule: String = "test-rule",
        severity: Severity = Severity.WARNING,
        fingerprint: String? = null,
        priority: Int = Finding.DEFAULT_PRIORITY,
        category: FindingCategory? = null,
    ): Finding =
        Finding(
            severity = severity,
            message = "Test message",
            rule = rule,
            fingerprint = fingerprint,
            priority = priority,
            category = category,
        )

    // --- deduplicated ---

    @Test
    fun `deduplicated keeps highest-severity finding per fingerprint`() {
        val findings = listOf(
            makeFinding(severity = Severity.WARNING, fingerprint = "abc"),
            makeFinding(severity = Severity.ERROR, fingerprint = "abc"),
            makeFinding(severity = Severity.INFO, fingerprint = "abc"),
        )
        val result = findings.deduplicated()
        assertEquals(1, result.size)
        assertEquals(Severity.ERROR, result.first().severity)
    }

    @Test
    fun `deduplicated keeps findings without fingerprint`() {
        val findings = listOf(
            makeFinding(fingerprint = null),
            makeFinding(fingerprint = null),
            makeFinding(fingerprint = "abc"),
        )
        val result = findings.deduplicated()
        assertEquals(3, result.size)
    }

    @Test
    fun `deduplicated with distinct fingerprints keeps all`() {
        val findings = listOf(
            makeFinding(fingerprint = "aaa"),
            makeFinding(fingerprint = "bbb"),
            makeFinding(fingerprint = "ccc"),
        )
        val result = findings.deduplicated()
        assertEquals(3, result.size)
    }

    @Test
    fun `deduplicated on empty list returns empty`() {
        val result = emptyList<Finding>().deduplicated()
        assertTrue(result.isEmpty())
    }

    // --- groupedByFingerprint ---

    @Test
    fun `groupedByFingerprint groups correctly`() {
        val findings = listOf(
            makeFinding(fingerprint = "aaa"),
            makeFinding(fingerprint = "aaa"),
            makeFinding(fingerprint = "bbb"),
            makeFinding(fingerprint = null),
        )
        val groups = findings.groupedByFingerprint()
        assertEquals(3, groups.size)
        assertEquals(2, groups["aaa"]?.size)
        assertEquals(1, groups["bbb"]?.size)
        assertEquals(1, groups[null]?.size)
    }

    // --- sortedByPriority ---

    @Test
    fun `sortedByPriority orders by priority then severity then rule`() {
        val findings = listOf(
            makeFinding(rule = "z-rule", priority = 50, severity = Severity.INFO),
            makeFinding(rule = "a-rule", priority = 10, severity = Severity.WARNING),
            makeFinding(rule = "b-rule", priority = 10, severity = Severity.ERROR),
            makeFinding(rule = "c-rule", priority = 200, severity = Severity.ERROR),
        )
        val sorted = findings.sortedByPriority()
        assertEquals(10, sorted[0].priority)
        assertEquals(Severity.ERROR, sorted[0].severity)
        assertEquals("b-rule", sorted[0].rule)
        assertEquals(10, sorted[1].priority)
        assertEquals(Severity.WARNING, sorted[1].severity)
        assertEquals(200, sorted[3].priority)
    }

    // --- inCategory ---

    @Test
    fun `inCategory filters to matching category`() {
        val findings = listOf(
            makeFinding(category = FindingCategory.SECURITY),
            makeFinding(category = FindingCategory.STYLE),
            makeFinding(category = FindingCategory.SECURITY),
            makeFinding(category = null),
        )
        val securityFindings = findings.inCategory(FindingCategory.SECURITY)
        assertEquals(2, securityFindings.size)
        assertTrue(securityFindings.all { it.category == FindingCategory.SECURITY })
    }

    @Test
    fun `inCategory returns empty when no match`() {
        val findings = listOf(
            makeFinding(category = FindingCategory.STYLE),
        )
        assertTrue(findings.inCategory(FindingCategory.PERFORMANCE).isEmpty())
    }
}
