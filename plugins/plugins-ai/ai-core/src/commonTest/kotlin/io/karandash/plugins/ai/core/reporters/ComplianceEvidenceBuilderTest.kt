package io.karandash.plugins.ai.core.reporters

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComplianceEvidenceBuilderTest {
    private fun finding(
        rule: String,
        severity: Severity,
        message: String = "msg",
    ) = Finding(severity = severity, message = message, rule = rule)

    @Test
    fun checklistHasPassForInfoOnlyRule() {
        val findings = listOf(finding("rule-a", Severity.INFO))
        val evidence = ComplianceEvidenceBuilder.build(findings, "cfg", "mr-1", "2026-01-01")
        val item = evidence.checklist.single()
        assertEquals("PASS", item.status)
        assertEquals("rule-a", item.rule)
    }

    @Test
    fun checklistHasFailForErrorRule() {
        val findings = listOf(finding("rule-b", Severity.ERROR))
        val evidence = ComplianceEvidenceBuilder.build(findings, "cfg", "mr-1", "2026-01-01")
        assertEquals("FAIL", evidence.checklist.single().status)
    }

    @Test
    fun checklistHasFailWhenAnyFindingIsWarning() {
        val findings = listOf(
            finding("rule-c", Severity.INFO),
            finding("rule-c", Severity.WARNING),
        )
        val evidence = ComplianceEvidenceBuilder.build(findings, "cfg", "mr-1", "t")
        assertEquals("FAIL", evidence.checklist.single().status)
    }

    @Test
    fun checklistGroupsByRule() {
        val findings = listOf(
            finding("rule-x", Severity.ERROR),
            finding("rule-x", Severity.WARNING),
            finding("rule-y", Severity.INFO),
        )
        val evidence = ComplianceEvidenceBuilder.build(findings, "cfg", "mr-2", "t")
        assertEquals(2, evidence.checklist.size)
        val ruleX = evidence.checklist.first { it.rule == "rule-x" }
        assertEquals(2, ruleX.findingCount)
        assertEquals("ERROR", ruleX.maxSeverity)
    }

    @Test
    fun checklistSortedByStatus() {
        val findings = listOf(
            finding("b-rule", Severity.INFO),
            finding("a-rule", Severity.ERROR),
        )
        val evidence = ComplianceEvidenceBuilder.build(findings, "cfg", "mr-3", "t")
        // FAIL < PASS alphabetically
        assertEquals("FAIL", evidence.checklist[0].status)
        assertEquals("PASS", evidence.checklist[1].status)
    }

    @Test
    fun integrityHashPopulatedAndNonEmpty() {
        val evidence = ComplianceEvidenceBuilder.build(emptyList(), "snap", "mr-99", "ts")
        assertTrue(evidence.integrityHash.isNotBlank())
    }

    @Test
    fun integrityHashIsSha256Hex() {
        val evidence = ComplianceEvidenceBuilder.build(emptyList(), "snap", "mr-99", "ts")
        assertEquals(io.karandash.crypto.sha256Hex("mr-99|ts|snap|0"), evidence.integrityHash)
    }

    @Test
    fun integrityHashDiffersForDifferentInputs() {
        val e1 = ComplianceEvidenceBuilder.build(emptyList(), "snap1", "mr-1", "t1")
        val e2 = ComplianceEvidenceBuilder.build(emptyList(), "snap2", "mr-2", "t2")
        assertFalse(e1.integrityHash == e2.integrityHash)
    }

    @Test
    fun findingsListReflectsPassedStatus() {
        val findings = listOf(
            finding("r", Severity.INFO, "ok"),
            finding("r", Severity.ERROR, "fail"),
        )
        val evidence = ComplianceEvidenceBuilder.build(findings, "cfg", "mr-1", "t")
        val passed = evidence.findings.filter { it.passed }
        val failed = evidence.findings.filter { !it.passed }
        assertEquals(1, passed.size)
        assertEquals(1, failed.size)
    }

    @Test
    fun formatReportContainsTable() {
        val findings = listOf(finding("lint", Severity.ERROR))
        val evidence = ComplianceEvidenceBuilder.build(findings, "cfg", "mr-5", "2026-03-01")
        val report = ComplianceEvidenceBuilder.formatReport(evidence)
        assertTrue(report.contains("## Compliance Evidence Pack"))
        assertTrue(report.contains("mr-5"))
        assertTrue(report.contains("| Rule | Status | Findings | Max Severity |"))
        assertTrue(report.contains("lint"))
        assertTrue(report.contains("FAIL"))
    }

    @Test
    fun formatReportContainsIntegrityHash() {
        val evidence = ComplianceEvidenceBuilder.build(emptyList(), "cfg", "mr-x", "t")
        val report = ComplianceEvidenceBuilder.formatReport(evidence)
        assertTrue(report.contains("Integrity: ${evidence.integrityHash}"))
    }

    @Test
    fun emptyFindingsProducesEmptyChecklist() {
        val evidence = ComplianceEvidenceBuilder.build(emptyList(), "cfg", "mr-0", "t")
        assertTrue(evidence.checklist.isEmpty())
        assertTrue(evidence.findings.isEmpty())
    }
}
