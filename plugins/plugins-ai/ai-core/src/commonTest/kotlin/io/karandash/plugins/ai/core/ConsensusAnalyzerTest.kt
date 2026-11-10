package io.karandash.plugins.ai.core

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsensusAnalyzerTest {
    private fun finding(
        rule: String = "ai-review",
        message: String = "issue found",
        severity: Severity = Severity.WARNING,
        path: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) = Finding(
        severity = severity,
        message = message,
        rule = rule,
        location = path?.let { Location.FileRef(it) },
        metadata = metadata,
    )

    @Test
    fun identicalFindingsFromTwoModelsAllKept() {
        val f = finding()
        val result = ConsensusAnalyzer.intersect(listOf(listOf(f), listOf(f)))
        assertEquals(1, result.findings.size)
        assertEquals(1, result.totalCandidates)
    }

    @Test
    fun findingInOnlyOneOfThreeModelsFilteredOut() {
        val common = finding(message = "shared issue")
        val unique = finding(message = "unique to one model only xyz")
        val set1 = listOf(common, unique)
        val set2 = listOf(common)
        val set3 = listOf(common)

        val result = ConsensusAnalyzer.intersect(listOf(set1, set2, set3))

        assertEquals(1, result.findings.size)
        assertEquals(common.rule, result.findings.first().rule)
        assertEquals(1, result.filteredCount)
    }

    @Test
    fun findingInTwoOfThreeModelsKeptMajority() {
        val f1 = finding(message = "majority issue here")
        val f2 = finding(message = "only one sees this completely different text")
        val set1 = listOf(f1, f2)
        val set2 = listOf(f1)
        val set3 = listOf(f1)

        val result = ConsensusAnalyzer.intersect(listOf(set1, set2, set3), minAgreement = 2)

        assertTrue(result.findings.any { it.message == f1.message })
    }

    @Test
    fun emptyFindingSetsReturnsEmptyResult() {
        val result = ConsensusAnalyzer.intersect(emptyList())
        assertTrue(result.findings.isEmpty())
        assertEquals(0, result.totalCandidates)
        assertEquals(1.0, result.agreementRate)
    }

    @Test
    fun singleFindingSetAllKept() {
        val findings = listOf(finding(message = "a"), finding(message = "b"))
        val result = ConsensusAnalyzer.intersect(listOf(findings))
        assertEquals(2, result.findings.size)
    }

    @Test
    fun matchingByFingerprintWorksAcrossSlightMessageVariations() {
        // Same rule + path + normalised message prefix → same fingerprint
        val f1 = finding(rule = "ai-review", message = "issue found in code 123", path = "src/Foo.kt")
        val f2 = finding(rule = "ai-review", message = "issue found in code 456", path = "src/Foo.kt")

        val fp1 = ConsensusAnalyzer.findingFingerprint(f1)
        val fp2 = ConsensusAnalyzer.findingFingerprint(f2)
        // Numbers stripped → same normalised message → same fingerprint
        assertEquals(fp1, fp2)
    }

    @Test
    fun consensusMetadataAddedToKeptFindings() {
        val f = finding()
        val result = ConsensusAnalyzer.intersect(listOf(listOf(f), listOf(f)))
        val kept = result.findings.first()
        assertTrue(kept.metadata.containsKey("consensusCount"))
        assertTrue(kept.metadata.containsKey("consensusModels"))
        assertEquals("2", kept.metadata["consensusCount"])
    }

    @Test
    fun agreementRateCalculatedCorrectly() {
        val shared = finding(message = "shared")
        val unique = finding(message = "unique only in first model zzzzzzzzzz")
        val result = ConsensusAnalyzer.intersect(
            listOf(listOf(shared, unique), listOf(shared)),
            minAgreement = 2,
        )
        // 2 candidates total, 1 agreed → 0.5
        assertEquals(0.5, result.agreementRate)
        assertEquals(2, result.totalCandidates)
    }

    @Test
    fun defaultMinAgreementReturnsMajority() {
        assertEquals(1, ConsensusAnalyzer.defaultMinAgreement(1))
        assertEquals(2, ConsensusAnalyzer.defaultMinAgreement(2))
        assertEquals(2, ConsensusAnalyzer.defaultMinAgreement(3))
        assertEquals(3, ConsensusAnalyzer.defaultMinAgreement(4))
        assertEquals(3, ConsensusAnalyzer.defaultMinAgreement(5))
        assertEquals(4, ConsensusAnalyzer.defaultMinAgreement(6))
    }

    @Test
    fun highestSeverityFindingKeptWhenMultipleMatch() {
        val warn = finding(severity = Severity.WARNING, message = "same issue")
        val error = finding(severity = Severity.ERROR, message = "same issue")

        val result = ConsensusAnalyzer.intersect(listOf(listOf(warn), listOf(error)))
        assertEquals(1, result.findings.size)
        assertEquals(Severity.ERROR, result.findings.first().severity)
    }

    @Test
    fun fingerprintDiffersForDifferentCommitRefs() {
        val f1 = Finding(
            severity = Severity.WARNING,
            message = "bad commit",
            rule = "ai-commit",
            location = Location.CommitRef("abc123"),
        )
        val f2 = Finding(
            severity = Severity.WARNING,
            message = "bad commit",
            rule = "ai-commit",
            location = Location.CommitRef("def456"),
        )
        val fp1 = ConsensusAnalyzer.findingFingerprint(f1)
        val fp2 = ConsensusAnalyzer.findingFingerprint(f2)
        assertTrue(fp1 != fp2, "Different commit refs should produce different fingerprints")
    }

    @Test
    fun filteredCountReflectsRemovedFindings() {
        val shared = finding(message = "shared across all")
        val solo1 = finding(rule = "ai-review", message = "only model zero zzz unique111")
        val solo2 = finding(rule = "ai-review", message = "only model one zzz unique222")
        val result = ConsensusAnalyzer.intersect(
            listOf(listOf(shared, solo1), listOf(shared, solo2)),
            minAgreement = 2,
        )
        // 3 unique fingerprints total, only 1 agreed
        assertEquals(1, result.findings.size)
        assertEquals(2, result.filteredCount)
    }
}
