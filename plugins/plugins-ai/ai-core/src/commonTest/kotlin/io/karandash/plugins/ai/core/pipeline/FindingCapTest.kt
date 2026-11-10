package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FindingCapTest {
    private fun comment(
        line: Int,
        category: String,
        confidence: Double,
        severity: Severity = Severity.WARNING,
        rule: String = AiRuleIds.REVIEW_COMMENTS,
    ) = Finding(
        severity = severity,
        message = "issue $category at $line",
        rule = rule,
        location = Location.FileRef("src/A.kt", line),
        metadata = mapOf(
            AiFindingMetadataKeys.CATEGORY to category,
            AiFindingMetadataKeys.CONFIDENCE to confidence.toString(),
            AiFindingMetadataKeys.AI_PROVIDER to "test",
        ),
    )

    @Test
    fun `protected findings survive past the cap`() {
        val items = List(4) { comment(it, "BUG", 0.8) } + comment(10, "CONV", 0.99, Severity.ERROR)

        val kept = FindingCap.keep(items, 2).findings

        assertEquals(4, kept.size)
        assertTrue(kept.all { it.metadata[AiFindingMetadataKeys.CATEGORY] == "BUG" })
    }

    @Test
    fun `a hard cap demotes protected findings past it to INFO keeping the surest`() {
        val items = listOf(comment(1, "BUG", 0.6), comment(2, "SEC", 0.95), comment(3, "BUG", 0.9), comment(4, "CONV", 0.99))

        val kept = FindingCap.keep(items, 1, hardMax = 2).findings

        assertEquals(listOf(1, 2, 3), kept.map { (it.location as Location.FileRef).line })
        assertEquals(listOf(Severity.INFO, Severity.WARNING, Severity.WARNING), kept.map { it.severity })
    }

    @Test
    fun `lower categories are ranked by severity then confidence and keep order`() {
        val items = listOf(comment(1, "CONV", 0.5), comment(2, "PERF", 0.9), comment(3, "TEST", 0.7, Severity.ERROR))

        val kept = FindingCap.keep(items, 2).findings

        assertEquals(listOf(2, 3), kept.map { (it.location as Location.FileRef).line })
    }

    @Test
    fun `per-file cap exempts BUG findings and does not count a suggestion twin`() {
        val bugs = List(3) { comment(it + 1, "BUG", 0.8) }
        val twin = comment(1, "BUG", 0.8, rule = AiRuleIds.CODE_SUGGESTIONS).let {
            it.copy(
                message = "fix",
                metadata =
                    it.metadata - AiFindingMetadataKeys.CATEGORY
            )
        }
        val conv = comment(20, "CONV", 0.9)

        val result = AiFindingFilter(FilterConfig(maxPerFile = 2)).filter(bugs + twin + conv)

        assertEquals(3, result.count { it.rule == AiRuleIds.REVIEW_COMMENTS })
        assertTrue(result.any { it.rule == AiRuleIds.CODE_SUGGESTIONS })
        assertTrue(result.none { it.metadata[AiFindingMetadataKeys.CATEGORY] == "CONV" })
    }

    @Test
    fun `an unconfirmed BUG below the cut becomes INFO with its rank and score`() {
        val confirmed = comment(1, "BUG", 0.9).grounded()
        val unconfirmed = comment(2, "BUG", 0.9).withMeta(AiFindingMetadataKeys.GROUNDING to "demoted")

        val result = FindingCap.keep(listOf(confirmed, unconfirmed), 1)

        assertEquals(listOf(Severity.WARNING, Severity.INFO), result.findings.map { it.severity })
        assertEquals("below-cap rank 2 score 0", result.findings[1].metadata[AiFindingMetadataKeys.DECISION_REASON])
        assertEquals(listOf(StageRecord.KEEP, StageRecord.DEMOTE), result.records.map { it.decision })
        assertEquals("WARNING", result.records[1].severityBefore)
        assertEquals("INFO", result.records[1].severityAfter)
    }

    @Test
    fun `a confirmed BUG is protected past the cap`() {
        val bugs = List(3) { comment(it + 1, "BUG", 0.6).grounded() }
        val nits = List(2) { comment(it + 10, "CONV", 0.99, Severity.ERROR).grounded() }

        val result = FindingCap.keep(bugs + nits, 2)

        assertEquals(3, result.findings.size)
        assertTrue(result.findings.all { it.metadata[AiFindingMetadataKeys.CATEGORY] == "BUG" && it.severity == Severity.WARNING })
    }

    @Test
    fun `a NIT below the cut is dropped and recorded`() {
        val result = FindingCap.keep(listOf(comment(1, "SEC", 0.9), comment(2, "NIT", 0.9)), 1)

        assertEquals(listOf(1), result.findings.map { (it.location as Location.FileRef).line })
        assertEquals(StageRecord.DROP, result.records[1].decision)
        assertTrue(result.records[1].reason.startsWith("below-cap rank 2 score 0"))
        assertTrue(result.records[0].reason.contains("score 1"))
    }

    @Test
    fun `a code-suggestions twin is dropped with its comment and not counted`() {
        val nit = comment(5, "NIT", 0.5)
        val twin = comment(5, "NIT", 0.5, rule = AiRuleIds.CODE_SUGGESTIONS)

        val result = FindingCap.keep(listOf(comment(1, "SEC", 0.9), nit, twin), 1)

        assertEquals(listOf(AiRuleIds.REVIEW_COMMENTS), result.findings.map { it.rule })
        assertEquals(StageRecord.DROP, result.records[2].decision)
    }

    @Test
    fun `an unknown protect condition is a config error naming the key`() {
        val error = assertFailsWith<ConfigParseException> { RankingConfig(protect = listOf("voted")).validated() }
        assertTrue(error.message.orEmpty().contains("ai.ranking.protect"))
    }

    @Test
    fun `a code suggestion takes its review comment confidence and fails min-confidence without one`() {
        val comment = comment(1, "BUG", 0.9)
        val twin = comment(1, "BUG", 0.1, rule = AiRuleIds.CODE_SUGGESTIONS)
        val orphan = comment(7, "BUG", 0.99, rule = AiRuleIds.CODE_SUGGESTIONS)
        val weak = comment(9, "BUG", 0.2)
        val weakTwin = comment(9, "BUG", 0.99, rule = AiRuleIds.CODE_SUGGESTIONS)

        val result = AiFindingFilter(FilterConfig(minConfidence = 0.5)).filter(listOf(comment, twin, orphan, weak, weakTwin))

        assertEquals(listOf(1 to AiRuleIds.REVIEW_COMMENTS, 1 to AiRuleIds.CODE_SUGGESTIONS), result.map { lineOf(it) to it.rule })
    }

    @Test
    fun `ranking weights decide which finding the cap keeps`() {
        val demoted = AiFindingMetadataKeys.GROUNDING to "demoted"
        val bug = comment(1, "BUG", 0.5).withMeta(AiFindingMetadataKeys.AGREEMENT to "agreed").withMeta(demoted)
        val sec = comment(2, "SEC", 0.5).withMeta(demoted)
        val perf = comment(3, "PERF", 0.5).grounded()

        fun kept(weights: RankingWeights) =
            FindingCap
                .keep(listOf(bug, sec, perf), 1, ranking = RankingConfig(weights = weights))
                .findings
                .single { it.severity == Severity.WARNING }
                .let { lineOf(it) }

        assertEquals(1, kept(RankingWeights(agreed = 5, grounded = 1, security = 1)))
        assertEquals(2, kept(RankingWeights(agreed = 1, grounded = 1, security = 5)))
        assertEquals(3, kept(RankingWeights(agreed = 1, grounded = 5, security = 1)))
    }

    @Test
    fun `a single-sample note takes no cap slot and is kept`() {
        val single = comment(9, "BUG", 0.5, Severity.INFO).withMeta(AiFindingMetadataKeys.AGREEMENT to "single")
        val items = listOf(comment(1, "PERF", 0.9), single, comment(2, "PERF", 0.8))

        val kept = FindingCap.keep(items, 2).findings

        assertEquals(listOf(1, 9, 2), kept.map { lineOf(it) })
    }

    private fun lineOf(finding: Finding) = (finding.location as Location.FileRef).line

    private fun Finding.grounded() = withMeta(AiFindingMetadataKeys.GROUNDING to "grounded")

    private fun Finding.withMeta(entry: Pair<String, String>) = copy(metadata = metadata + entry)
}
