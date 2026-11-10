package io.karandash.plugins.formats

import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals

/** Findings of one rule that share a hint show that hint once, not once per row. */
class MarkdownRepeatedHintsTest {
    private fun finding(
        sha: String,
        rule: String = "commitMessages",
        suggestion: String = "Shorten the subject",
    ) = Finding(
        severity = Severity.WARNING,
        message = "Commit message too long: $sha",
        rule = rule,
        suggestion = suggestion,
        explanation = "Short subjects keep git log readable",
        metadata = mapOf(
            WellKnownMetadataKeys.EXPECTED_FORMAT to "Description",
            WellKnownMetadataKeys.FORMAT_EXPLAIN to "explain block",
        ),
    )

    private fun summary(findings: List<Finding>): String {
        val r = ValidationReport(
            passed = false,
            findings = findings,
            summary = findings.summarize(),
            groupedByLocation = emptyMap(),
            inlineEligible = emptyList(),
            summaryOnly = findings,
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
        )
        return formatSummaryMarkdown(r, r.summaryOnly, inlineCount = 0)
    }

    private fun String.count(part: String) = windowed(part.length).count { it == part }

    @Test
    fun `repeated hints of one rule render once`() {
        val md = summary(listOf(finding("aaa"), finding("bbb"), finding("ccc")))

        assertEquals(3, md.count("Commit message too long"), md)
        assertEquals(1, md.count("Shorten the subject"), md)
        assertEquals(1, md.count("Short subjects keep git log readable"), md)
        assertEquals(1, md.count("explain block"), md)
    }

    @Test
    fun `different hints and other rules still render`() {
        val md = summary(listOf(finding("aaa"), finding("bbb", suggestion = "Drop the prefix"), finding("ccc", rule = "title")))

        assertEquals(2, md.count("Shorten the subject"), md)
        assertEquals(1, md.count("Drop the prefix"), md)
        assertEquals(2, md.count("Short subjects keep git log readable"), md)
    }

    @Test
    fun `code spans in a hint stay code in the table while the prose is escaped`() {
        val md = summary(listOf(finding("aaa", suggestion = "Use @team or a|b, e.g. `feat: x|y`.")))

        assertEquals(1, md.count("Use @\u200Bteam or a\\|b, e.g. `feat: x\\|y`."), md)
    }
}
