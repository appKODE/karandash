package io.karandash.plugins.formats

import io.karandash.api.finding.ComplianceItem
import io.karandash.api.finding.ComplianceStatus
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ReviewCoverage
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The summary shows one risk block, honest rule status and keeps policy detail collapsed. */
class MarkdownSummaryHonestyTest {
    private fun finding(
        message: String,
        metadata: Map<String, String> = emptyMap(),
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = "r",
        location = Location.FileRef(path = "src/A.kt", line = 3),
        metadata = metadata,
    )

    private fun summary(
        findings: List<Finding>,
        checklist: List<ComplianceItem> = emptyList(),
        skipped: List<SkippedRuleInfo> = emptyList(),
        provenance: ReviewProvenance? = null,
    ): String {
        val report = ValidationReport(
            passed = true,
            findings = findings,
            summary = findings.summarize(),
            groupedByLocation = emptyMap(),
            inlineEligible = emptyList(),
            summaryOnly = findings,
            complianceChecklist = checklist,
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
            skippedRules = skipped,
            provenance = provenance,
        )
        return formatSummaryMarkdown(report, report.summaryOnly, inlineCount = 0)
    }

    private fun risk(
        score: String,
        message: String,
    ) = finding(message, mapOf(WellKnownMetadataKeys.IS_RISK_ADVISORY to "true", WellKnownMetadataKeys.RISK_SCORE to score))

    @Test
    fun `duplicate risk findings render one block with the highest score`() {
        val md = summary(listOf(risk("30", "chunk one"), risk("70", "chunk two"), finding("plain")))

        assertEquals(1, md.split("Risk Assessment").size - 1, md)
        assertTrue("(score: 70)" in md && "chunk two" in md, md)
        assertFalse("chunk one" in md, md)
    }

    @Test
    fun `policy expressions stay out of the visible summary`() {
        val policy = SkippedRuleInfo(
            "titleFormat",
            "policy 'ticket' override not applied (when: title_matches(\"@any-ticket\"))",
            SkipCategory.POLICY_OVERRIDE_NOT_APPLIED,
            policy = "ticket",
        )
        val md = summary(listOf(finding("plain")), skipped = listOf(policy, SkippedRuleInfo("x", "draft MR")))

        assertFalse("title_matches" in md, md)
        assertTrue("<details><summary>Policies not applied (1)</summary>" in md, md)
        assertTrue("- ticket: `titleFormat`" in md, md)
        assertTrue("<details><summary>Skipped rules (1)</summary>\n\n- `x`: draft MR\n" in md, md)
    }

    @Test
    fun `skipped rule shows as skipped in the checklist not as passed`() {
        val md = summary(
            listOf(finding("plain")),
            checklist = listOf(ComplianceItem("ok", ComplianceStatus.PASSED)),
            skipped = listOf(SkippedRuleInfo("aiReview", "budget exhausted", SkipCategory.AI_BUDGET_EXHAUSTED)),
        )

        assertTrue("- ⏭️ aiReview (skipped)\n" in md, md)
        assertTrue("- ✅ ok\n" in md, md)
    }

    @Test
    fun `policy not applied does not mark the rule skipped`() {
        val md = summary(
            listOf(finding("plain")),
            checklist = listOf(ComplianceItem("titleFormat", ComplianceStatus.FAILED)),
            skipped = listOf(SkippedRuleInfo("titleFormat", "policy 'p' override not applied", SkipCategory.POLICY_OVERRIDE_NOT_APPLIED)),
        )

        assertTrue("- ❌ titleFormat\n" in md, md)
        assertFalse("⏭️" in md, md)
    }

    @Test
    fun `budget skipped run without findings shows the skip and the AI coverage`() {
        val md = summary(
            emptyList(),
            skipped = listOf(SkippedRuleInfo("aiReview", "budget exhausted", SkipCategory.AI_BUDGET_EXHAUSTED)),
            provenance = ReviewProvenance(ReviewCoverage(totalFiles = 2, reviewedFiles = 0, chunksSkipped = 2)),
        )

        assertTrue("Skipped rules (1)" in md, md)
        assertTrue("_AI coverage: reviewed 0/2 files, 2 chunks skipped, 0 truncated_" in md, md)
    }

    @Test
    fun `clean run without skips or AI stays a plain pass`() {
        val md = summary(emptyList())

        assertTrue("All validation checks passed. No issues found." in md, md)
        assertFalse("Skipped rules" in md || "AI coverage" in md, md)
    }
}
