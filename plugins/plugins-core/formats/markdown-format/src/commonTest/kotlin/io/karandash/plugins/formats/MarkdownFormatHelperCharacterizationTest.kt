package io.karandash.plugins.formats

import io.karandash.api.finding.ComplianceItem
import io.karandash.api.finding.ComplianceStatus
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Characterization of [MarkdownFormatHelper] output for the parts the review fixes touch. */
class MarkdownFormatHelperCharacterizationTest {
    private fun finding(
        message: String = "msg",
        severity: Severity = Severity.WARNING,
        rule: String = "r",
        metadata: Map<String, String> = emptyMap(),
    ) = Finding(
        severity = severity,
        message = message,
        rule = rule,
        location = Location.FileRef(path = "src/A.kt", line = 3),
        metadata = metadata,
    )

    private fun report(
        findings: List<Finding>,
        checklist: List<ComplianceItem> = emptyList(),
        skipped: List<SkippedRuleInfo> = emptyList(),
    ) = ValidationReport(
        passed = findings.none { it.severity == Severity.ERROR },
        findings = findings,
        summary = findings.summarize(),
        groupedByLocation = emptyMap(),
        inlineEligible = emptyList(),
        summaryOnly = findings,
        complianceChecklist = checklist,
        explanations = emptyMap(),
        metadata = ReportMetadata(version = "test", timestamp = 0L),
        skippedRules = skipped,
    )

    private fun summary(report: ValidationReport) = formatSummaryMarkdown(report, report.summaryOnly, inlineCount = 0)

    @Test
    fun inlineHeaderMessageSuggestionAndExplanation() {
        val f = finding(message = "uses \$HOME").copy(suggestion = "do x", explanation = "because")

        val body = formatInlineCommentBody(f)

        assertTrue(body.startsWith("**⚠️ WARNING** — `r` · uses \\\$HOME"), body)
        assertTrue("\n\n**How to fix:** do x" in body, body)
        assertTrue("\n\nℹ️ because" in body, body)
    }

    @Test
    fun inlineHidesKnownMetadataKeys() {
        val body = formatInlineCommentBody(finding(metadata = mapOf(WellKnownMetadataKeys.CONFIDENCE to "0.9")))

        assertTrue("confidence" !in body, body)
    }

    @Test
    fun inlineDropsFencedCodeInMessage() {
        val body = formatInlineCommentBody(finding(message = "Fix:\n```kotlin\nval x = 1\n```"))

        assertTrue("```" !in body.substringBefore("<!--"), body)
        assertTrue(body.startsWith("**⚠️ WARNING** — `r` · Fix:\n<!--"), body)
    }

    @Test
    fun summaryRowSanitizesNewlinesAndPipes() {
        val md = summary(report(listOf(finding(message = "a\nb | c"))))

        assertTrue("| ⚠️ WARNING | `r` | a b \\| c |" in md, md)
    }

    @Test
    fun summaryRendersOneRiskBlockPerRiskFinding() {
        val risk = mapOf(WellKnownMetadataKeys.IS_RISK_ADVISORY to "true", WellKnownMetadataKeys.RISK_SCORE to "40")
        val md = summary(report(listOf(finding(message = "risk one", metadata = risk), finding(message = "plain"))))

        assertEquals(1, Regex("Risk Assessment").findAll(md).count(), md)
        assertTrue("> **⚠️ Risk Assessment** (score: 40)" in md, md)
    }

    @Test
    fun checklistUsesHonestIconsForPassedWarningAndFailed() {
        val checklist = listOf(
            ComplianceItem("ok", ComplianceStatus.PASSED),
            ComplianceItem("warn", ComplianceStatus.WARNING),
            ComplianceItem("bad", ComplianceStatus.FAILED),
        )
        val md = summary(report(listOf(finding()), checklist = checklist))

        assertTrue("- ✅ ok\n" in md, md)
        assertTrue("- ⚠️ warn (warnings)\n" in md, md)
        assertTrue("- ❌ bad\n" in md, md)
    }

    @Test
    fun skippedRulesGoInDetails() {
        val md = summary(report(listOf(finding()), skipped = listOf(SkippedRuleInfo("x", "draft MR"))))

        assertTrue("<details><summary>Skipped rules (1)</summary>\n\n- `x`: draft MR\n" in md, md)
    }

    @Test
    fun emptyReportSaysAllPassed() {
        val md = summary(report(emptyList()))

        assertTrue(md.startsWith("## Karandash Compliance: PASSED\n\nAll validation checks passed. No issues found."), md)
    }
}
