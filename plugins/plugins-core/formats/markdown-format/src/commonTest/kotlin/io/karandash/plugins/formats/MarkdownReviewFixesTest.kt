package io.karandash.plugins.formats

import io.karandash.api.finding.InlineCommentData
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

/** Review fixes: truncation tail, policy grouping, code-vs-prose, newline escapes, table cells. */
class MarkdownReviewFixesTest {
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
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
            skippedRules = skipped,
            provenance = provenance,
        )
        return formatSummaryMarkdown(report, report.summaryOnly, inlineCount = 0)
    }

    private fun suggestedCodeBody(code: String) =
        formatInlineCommentBody(
            finding("msg", mapOf(WellKnownMetadataKeys.SUGGESTED_CODE to code)).copy(suggestion = "Handle the null case"),
        )

    private val coverage = ReviewProvenance(ReviewCoverage(totalFiles = 140, reviewedFiles = 90, chunksSkipped = 2, truncatedFiles = 1))

    private fun hugeFencedSummary(
        withCoverage: Boolean,
        skippedCount: Int,
    ): String {
        val code = (1..900).joinToString("\n") { "val line$it = compute($it)" }
        val huge = finding("Changes:\n```kotlin\n$code\n```", mapOf(WellKnownMetadataKeys.IS_MR_SUMMARY to "true"))
        val skipped = (1..skippedCount).map { SkippedRuleInfo("skipped-$it", "not run") }
        return summary(listOf(huge, finding("small")), skipped, if (withCoverage) coverage else null)
    }

    private fun fenceCount(md: String) = md.lines().count { it.trimStart().startsWith("```") }

    @Test
    fun `over limit summary keeps the AI coverage line and the skipped rule count`() {
        val md = hugeFencedSummary(withCoverage = true, skippedCount = 4)

        assertTrue(md.encodeToByteArray().size <= SUMMARY_MAX_BYTES, "summary is ${md.encodeToByteArray().size} bytes")
        assertTrue("_AI coverage: reviewed 90/140 files, 2 chunks skipped, 1 truncated_" in md, md.takeLast(400))
        assertTrue("4 rules skipped" in md, md.takeLast(400))
    }

    @Test
    fun `over limit summary closes an open code fence`() {
        val md = hugeFencedSummary(withCoverage = true, skippedCount = 0)

        assertEquals(0, fenceCount(md) % 2, "unbalanced fences: " + md.takeLast(300))
        assertEquals(md.split("<details>").size, md.split("</details>").size)
    }

    @Test
    fun `over limit summary without coverage or skips adds no coverage or skip line`() {
        val md = hugeFencedSummary(withCoverage = false, skippedCount = 0)

        assertFalse("AI coverage" in md, md.takeLast(300))
        assertFalse("rules skipped" in md, md.takeLast(300))
        assertTrue(md.trimEnd().endsWith(SUMMARY_FOOTER))
    }

    @Test
    fun `small summary with balanced fences is unchanged`() {
        val md = summary(listOf(finding("Changes:\n```kotlin\nval a = 1\n```", mapOf(WellKnownMetadataKeys.IS_MR_SUMMARY to "true"))))

        assertEquals(2, fenceCount(md))
        assertFalse("rules skipped" in md)
    }

    @Test
    fun `policies not applied are grouped by the policy field`() {
        val skips = listOf(
            SkippedRuleInfo("a", "any reason text", SkipCategory.POLICY_OVERRIDE_NOT_APPLIED, policy = "hotfix"),
            SkippedRuleInfo("b", "other text (when: x == 1)", SkipCategory.POLICY_OVERRIDE_NOT_APPLIED, policy = "hotfix"),
            SkippedRuleInfo("c", "text", SkipCategory.POLICY_OVERRIDE_NOT_APPLIED, policy = "docs"),
        )

        val md = summary(listOf(finding("x")), skips)

        assertTrue("Policies not applied (2)" in md, md)
        assertTrue("- hotfix: `a`, `b`" in md, md)
        assertTrue("- docs: `c`" in md, md)
    }

    @Test
    fun `policy skip without a policy label is grouped as unnamed and hides the condition`() {
        val skips = listOf(SkippedRuleInfo("a", "policy 'p' not applied (when: x == 1)", SkipCategory.POLICY_OVERRIDE_NOT_APPLIED))

        val md = summary(listOf(finding("x")), skips)

        assertTrue("- unnamed policy: `a`" in md, md)
        assertFalse("when:" in md, md)
    }

    @Test
    fun `shell and yaml lines are rendered as code`() {
        assertTrue("+ rm -rf build dist out cache tmp" in suggestedCodeBody("rm -rf build dist out cache tmp"))
        assertTrue("+ name: build and test all the things" in suggestedCodeBody("name: build and test all the things"))
        assertTrue("+ import kotlin.collections.List." in suggestedCodeBody("import kotlin.collections.List."))
        assertTrue("+ ./gradlew build now please run it" in suggestedCodeBody("./gradlew build now please run it"))
    }

    @Test
    fun `sentence shaped prose is still dropped`() {
        assertFalse("```" in suggestedCodeBody("Consider extracting this logic into a separate helper function."))
        assertFalse("```" in suggestedCodeBody("This is a well-known pattern, so keep it."))
        assertFalse(InlineCommentData.SUGGESTION_START in suggestedCodeBody("Done."))
    }

    @Test
    fun `double backtick span holding a backtick keeps its backslash n`() {
        val out = unescapeNewlines("see ``a`b\\nc`` then\\nnext")

        assertEquals("see ``a`b\\nc`` then\nnext", out)
    }

    @Test
    fun `single backtick span and fence still keep backslash n`() {
        assertEquals("`a\\nb` x\ny", unescapeNewlines("`a\\nb` x\\ny"))
        assertEquals("```\na\\nb\n```\nz", unescapeNewlines("```\na\\nb\n```\\nz"))
    }

    @Test
    fun `windows path and regex in prose keep their backslash n`() {
        assertEquals("open C:\\new\\notes first", unescapeNewlines("open C:\\new\\notes first"))
        assertEquals("match \\d+\\n lines", unescapeNewlines("match \\d+\\n lines"))
        assertEquals("a\\\\nb", unescapeNewlines("a\\\\nb"))
    }

    @Test
    fun `plain prose backslash n still becomes a newline`() {
        assertEquals("one\ntwo\n\nthree", unescapeNewlines("one\\ntwo\\n\\nthree"))
    }

    // X8

    @Test
    fun `summary table cell never shows a literal backslash n or breaks the row`() {
        val md = summary(listOf(finding("first\\nsecond").copy(explanation = "why\\nbecause")))

        val row = md.lines().single { it.startsWith("| ⚠️ WARNING") }
        assertTrue("first second" in row, row)
        assertTrue("why because" in row, row)
        assertFalse("\\n" in row, row)
    }

    @Test
    fun `demoted finding is marked unverified in the summary and an anchored one is not`() {
        val demoted = finding(
            "off anchor",
            mapOf(WellKnownMetadataKeys.LINE_DEMOTED to "true")
        ).copy(location = Location.FileRef("src/B.kt"))
        val md = summary(listOf(demoted, finding("on anchor")))

        assertTrue("off anchor <br><small>unverified anchor in `src/B.kt`</small>" in md, md)
        assertEquals(1, md.split("unverified anchor").size - 1, md)
    }

    @Test
    fun `coverage line pluralises chunks`() {
        fun line(skipped: Int) = summary(listOf(finding("x")), provenance = ReviewProvenance(ReviewCoverage(3, 2, skipped, 0)))

        assertTrue("1 chunk skipped" in line(1), line(1))
        assertTrue("2 chunks skipped" in line(2), line(2))
        assertTrue("0 chunks skipped" in line(0), line(0))
    }
}
