package io.karandash.plugins.ai.core.architecture

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvenanceChainTest {
    private fun finding(
        rule: String = "title-format",
        severity: Severity = Severity.ERROR,
        message: String = "bad title"
    ) = Finding(severity = severity, message = message, rule = rule)

    @Test
    fun emptyFindingsProducesEmptyChain() {
        val result = ProvenanceChain.build(emptyList(), "cfghash", "mrhash", "1.7.0")
        assertTrue(result.entries.isEmpty())
        assertEquals(0, result.findingCount)
    }

    @Test
    fun chainHashIsDeterministic() {
        val findings = listOf(finding())
        val r1 = ProvenanceChain.build(findings, "cfghash", "mrhash", "1.7.0")
        val r2 = ProvenanceChain.build(findings, "cfghash", "mrhash", "1.7.0")
        assertEquals(r1.chainHash, r2.chainHash)
    }

    @Test
    fun hashesAreSha256Hex() {
        val result = ProvenanceChain.build(listOf(finding("r1")), "cfghash", "mrhash", "1.7.0")
        val isSha256Hex = { h: String -> h.length == 64 && h.all { it in "0123456789abcdef" } }
        assertTrue(isSha256Hex(result.entries.single().findingHash))
        assertTrue(isSha256Hex(result.chainHash))
    }

    @Test
    fun verifyReturnsTrueForValidChain() {
        val findings = listOf(finding(), finding(rule = "branch-naming", severity = Severity.WARNING))
        val result = ProvenanceChain.build(findings, "cfghash", "mrhash", "1.7.0")
        assertTrue(ProvenanceChain.verify(result))
    }

    @Test
    fun entryCountMatchesFindingCount() {
        val findings = listOf(finding(), finding(rule = "draft"), finding(rule = "size-limits"))
        val result = ProvenanceChain.build(findings, "cfghash", "mrhash", "1.7.0")
        assertEquals(3, result.entries.size)
        assertEquals(3, result.findingCount)
    }

    @Test
    fun formatReportReadable() {
        val findings = listOf(finding())
        val result = ProvenanceChain.build(findings, "cfghash", "mrhash", "1.7.0")
        val report = ProvenanceChain.formatReport(result)
        assertTrue(report.contains("Provenance Chain"))
        assertTrue(report.contains("Chain hash:"))
        assertTrue(report.contains("Findings: 1"))
    }

    @Test
    fun verifyReturnsTrueForEmptyChain() {
        val result = ProvenanceChain.build(emptyList(), "cfghash", "mrhash", "1.7.0")
        assertTrue(ProvenanceChain.verify(result))
    }

    @Test
    fun entriesLinkPreviousHashCorrectly() {
        val findings = listOf(finding(), finding(rule = "draft"))
        val result = ProvenanceChain.build(findings, "cfghash", "mrhash", "1.7.0")
        assertEquals("cfghash", result.entries.first().previousHash)
        assertEquals(result.entries[0].findingHash, result.entries[1].previousHash)
    }
}
