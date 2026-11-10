package io.karandash.plugins.formats

import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every metadata key the comment does not show elsewhere renders as a chip (`hideFields: [metadata]` hides them); keys with their own place, like the anchor fingerprint, are not repeated.
 */
class MarkdownMetadataAllowlistTest {
    private val finding = Finding(
        severity = Severity.WARNING,
        message = "msg",
        rule = "r",
        location = Location.FileRef(path = "src/A.kt", line = 3),
        metadata = mapOf(
            "reclassifiedFrom" to "ai-review",
            "reclassifiedReason" to "duplicate of lint",
            "someInternalKey" to "x",
            WellKnownMetadataKeys.ANCHOR_FINGERPRINT to "0123456789abcdef",
            WellKnownMetadataKeys.BUG_INTRODUCED_BY to "abc1234",
        ),
    )

    private val report = ValidationReport(
        passed = true,
        findings = listOf(finding),
        summary = listOf(finding).summarize(),
        groupedByLocation = emptyMap(),
        inlineEligible = emptyList(),
        summaryOnly = listOf(finding),
        complianceChecklist = emptyList(),
        explanations = emptyMap(),
        metadata = ReportMetadata(version = "test", timestamp = 0L),
    )

    @Test
    fun `inline comment shows every metadata key not rendered elsewhere`() {
        val body = formatInlineCommentBody(finding)

        assertTrue("`reclassifiedFrom: ai-review`" in body, body)
        assertTrue("`someInternalKey: x`" in body, body)
        assertFalse(WellKnownMetadataKeys.ANCHOR_FINGERPRINT in body, body)
        assertTrue("`bugIntroducedBy: abc1234`" in body, body)
    }

    @Test
    fun `summary row shows every metadata key not rendered elsewhere`() {
        val md = formatSummaryMarkdown(report, report.summaryOnly, inlineCount = 0)

        assertTrue("`reclassifiedFrom: ai-review`" in md, md)
        assertTrue("`someInternalKey: x`" in md, md)
        assertFalse(WellKnownMetadataKeys.ANCHOR_FINGERPRINT in md, md)
        assertTrue("`bugIntroducedBy: abc1234`" in md, md)
    }

    /** The drift, code-quality and parse-error metadata the AI writes reach the comment. */
    @Test
    fun `driftScore overallScore and error render as chips`() {
        val ai = finding.copy(
            metadata = mapOf(
                WellKnownMetadataKeys.DRIFT_SCORE to "0.40",
                WellKnownMetadataKeys.OVERALL_SCORE to "72",
                WellKnownMetadataKeys.ERROR to "parse_error",
            ),
        )
        val body = formatInlineCommentBody(ai)

        listOf("`driftScore: 0.40`", "`overallScore: 72`", "`error: parse_error`").forEach { assertTrue(it in body, body) }
    }
}
