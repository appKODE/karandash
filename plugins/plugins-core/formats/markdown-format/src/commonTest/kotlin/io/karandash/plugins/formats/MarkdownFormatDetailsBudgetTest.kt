package io.karandash.plugins.formats

import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The collapsed `Format` blocks count toward the compact summary budget and cannot break out of their block. */
class MarkdownFormatDetailsBudgetTest {
    private fun finding(
        i: Int,
        explain: String,
        diagram: String,
    ) = Finding(
        severity = Severity.ERROR,
        message = "Title $i does not match the required pattern of the project for merge requests in this repository",
        rule = "title-format-$i",
        suggestion = "Change the title to match the format",
        metadata = mapOf(WellKnownMetadataKeys.FORMAT_EXPLAIN to explain, WellKnownMetadataKeys.FORMAT_DIAGRAM to diagram),
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

    @Test
    fun `compact summary drops the diagrams first and keeps the explanations`() {
        val md = summary((1..10).map { finding(it, "explanation number $it", "D".repeat(1_000)) })

        assertTrue("<details><summary>Findings (10)</summary>" in md, md)
        assertFalse(FENCE_START in md, md)
        assertTrue("explanation number 10" in md, md)
        assertTrue(md.encodeToByteArray().size <= SUMMARY_MAX_BYTES)
        assertFalse("truncated" in md.lowercase(), md)
    }

    @Test
    fun `compact summary drops the explanations too when they still do not fit`() {
        val md = summary((1..10).map { finding(it, "E".repeat(400), "D".repeat(1_000)) })

        assertTrue("<details><summary>Findings (10)</summary>" in md, md)
        assertFalse("<summary>Format" in md, md)
        assertFalse("truncated" in md.lowercase(), md)
    }

    @Test
    fun `explanation and diagram cannot break out of the format block`() {
        val md = summary(listOf(finding(1, "a | b </details> `c`", "x ``` y\n````z")))

        assertTrue("a \\| b &lt;/details&gt; \\`c\\`" in md, md)
        assertTrue("`````\nx ``` y\n````z\n`````" in md, md)
        assertEquals(md.split("<details>").size, md.split("</details>").size, "unbalanced details tags")
    }

    private companion object {
        const val FENCE_START = "```"
    }
}
