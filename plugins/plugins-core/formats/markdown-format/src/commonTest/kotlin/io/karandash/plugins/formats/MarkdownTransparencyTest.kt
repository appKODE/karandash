package io.karandash.plugins.formats

import io.karandash.api.finding.AnalyzerProvenance
import io.karandash.api.finding.EnricherProvenance
import io.karandash.api.finding.FilterProvenance
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ReviewCoverage
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T: the `report.transparency` provenance block of the markdown summary. */
class MarkdownTransparencyTest {
    private val provenance = ReviewProvenance(
        coverage = ReviewCoverage(totalFiles = 3, reviewedFiles = 2, chunksSkipped = 1),
        enrichers = listOf(
            EnricherProvenance("jira", ProvenanceStatus.SKIPPED, reason = "JIRA_TOKEN not set"),
            EnricherProvenance("ci", ProvenanceStatus.OK, tokens = 120),
        ),
        analyzers = listOf(AnalyzerProvenance("aiReview", model = "gpt-4o", inputTokens = 900, outputTokens = 80, findings = 2)),
        filters = listOf(FilterProvenance(FilterProvenance.FEEDBACK, removed = 1)),
    )

    private fun report(p: ReviewProvenance?) =
        ValidationReport(
            passed = true,
            findings = emptyList(),
            summary = emptyList<io.karandash.models.finding.Finding>().summarize(),
            groupedByLocation = emptyMap(),
            inlineEligible = emptyList(),
            summaryOnly = emptyList(),
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
            provenance = p,
        )

    @Test
    fun `markdown summary renders provenance coverage line`() {
        val md = formatSummaryMarkdown(report(provenance), emptyList(), inlineCount = 0, MarkdownTransparencyConfig())

        val block = md.substringAfter("<details><summary>How this review was made</summary>", "").substringBefore("</details>")
        assertTrue("reviewed 2/3 files, 1 chunk skipped, 0 truncated" in block, md)
    }

    @Test
    fun `a huge provenance block in expanded mode with the diagram keeps the summary under the size limit`() {
        val longReason = "provider said no ".repeat(150)
        val huge = provenance.copy(
            enrichers = (1..6).map { EnricherProvenance("enricher-$it", ProvenanceStatus.FAILED, reason = longReason) },
            analyzers = (1..15).map { AnalyzerProvenance("analyzer-$it", status = ProvenanceStatus.FAILED, reason = longReason) },
        )
        val config = MarkdownTransparencyConfig(TransparencySummary.EXPANDED, flowDiagram = true)

        val md = formatSummaryMarkdown(report(huge), emptyList(), inlineCount = 0, config)

        assertTrue(md.encodeToByteArray().size <= SUMMARY_MAX_BYTES, "summary is ${md.encodeToByteArray().size} bytes")
        assertTrue("_AI coverage: reviewed 2/3 files" in md, md)
        assertTrue("job artifact" in md, md)
    }

    @Test
    fun `an ok enricher row tells its items, no data or cached`() {
        val p = provenance.copy(
            enrichers = listOf(
                EnricherProvenance("git", ProvenanceStatus.OK, items = 2),
                EnricherProvenance("ci", ProvenanceStatus.OK),
                EnricherProvenance("jira", ProvenanceStatus.OK, cached = true),
            ),
        )

        val block = provenanceBlock(p, MarkdownTransparencyConfig())

        assertTrue("| `git` | ok | 2 items |" in block, block)
        assertTrue("| `ci` | ok | no data |" in block, block)
        assertTrue("| `jira` | ok | cached |" in block, block)
    }

    @Test
    fun `a provenance reason is cut to a bounded length in the block`() {
        val p = provenance.copy(enrichers = listOf(EnricherProvenance("jira", ProvenanceStatus.FAILED, reason = "x".repeat(1000))))

        val block = provenanceBlock(p, MarkdownTransparencyConfig())

        assertFalse("x".repeat(PROVENANCE_REASON_MAX + 1) in block, block)
        assertTrue("x".repeat(PROVENANCE_REASON_MAX - 1) + "\u2026" in block, block)
    }

    @Test
    fun `a provenance reason cut at an emoji drops the whole emoji`() {
        val reason = "x".repeat(PROVENANCE_REASON_MAX - 2) + "\uD83D\uDE00" + "tail"
        val p = provenance.copy(enrichers = listOf(EnricherProvenance("jira", ProvenanceStatus.FAILED, reason = reason)))

        val block = provenanceBlock(p, MarkdownTransparencyConfig())

        assertTrue("x".repeat(PROVENANCE_REASON_MAX - 2) + "\u2026" in block, block)
        assertFalse('\uD83D' in block, block)
    }

    @Test
    fun `a small provenance block renders whole in the summary`() {
        val config = MarkdownTransparencyConfig(TransparencySummary.EXPANDED, flowDiagram = true)

        val md = formatSummaryMarkdown(report(provenance), emptyList(), inlineCount = 0, config)

        assertTrue(provenanceBlock(provenance, config) in md, md)
        assertFalse("job artifact" in md, md)
    }

    @Test
    fun `a negative or non-finite analyzer cost renders as zero`() {
        val p = provenance.copy(
            analyzers = listOf(-1.5, Double.NaN, Double.POSITIVE_INFINITY).mapIndexed { i, c -> AnalyzerProvenance("a$i", costUsd = c) },
        )

        val block = provenanceBlock(p, MarkdownTransparencyConfig(TransparencySummary.EXPANDED))

        assertEquals(3, Regex("""\| \$0\.0000 \|""").findAll(block).count(), block)
    }

    @Test
    fun `a positive analyzer cost renders to four decimals`() {
        val p = provenance.copy(analyzers = listOf(AnalyzerProvenance("a", costUsd = 1.23456)))

        val block = provenanceBlock(p, MarkdownTransparencyConfig(TransparencySummary.EXPANDED))

        assertTrue("| $1.2346 |" in block, block)
    }
}
