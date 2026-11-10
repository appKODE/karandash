package io.karandash.plugins.ai

import io.karandash.api.finding.FilterProvenance
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.dispatch.PostProcessResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T: the AI rule's [io.karandash.api.finding.ReviewProvenance] and per-finding why. */
class AiRuleProvenanceTest {
    private fun finding(message: String) = Finding(severity = Severity.WARNING, message = message, rule = "aiReview")

    @Test
    fun `provenance lists ran and skipped analyzers sorted with model cost and feedback suppressions`() {
        val state = AiRuleState(
            ruleInputTokens = mapOf("aiReview" to 900),
            ruleOutputTokens = mapOf("aiReview" to 80),
            ruleSkipReasons = mapOf(
                "ticketCompliance" to SkippedRuleInfo("ticketCompliance", "budget exhausted", SkipCategory.AI_BUDGET_EXHAUSTED),
            ),
        ).withAnalyzerRun(
            "aiReview",
            "gpt-4o",
            PostProcessResult(listOf(finding("a"), finding("b")), 980, 10, 0.25, feedbackSuppressed = 1),
        )

        val p = checkNotNull(state.provenance())

        assertEquals(listOf("aiReview", "ticketCompliance"), p.analyzers.map { it.ruleId })
        val ran = p.analyzers[0]
        assertEquals(ProvenanceStatus.OK, ran.status)
        assertEquals("gpt-4o", ran.model)
        assertEquals(900 to 80, ran.inputTokens to ran.outputTokens)
        assertEquals(0.25, ran.costUsd)
        assertEquals(2, ran.findings)
        assertEquals(ProvenanceStatus.SKIPPED, p.analyzers[1].status)
        assertEquals("budget exhausted", p.analyzers[1].reason)
        assertEquals(listOf(FilterProvenance(FilterProvenance.FEEDBACK, 1)), p.filters)
    }

    @Test
    fun `provenance carries each rule's context budget line`() {
        val state = AiRuleState(
            ruleModels = mapOf("a" to "m", "b" to "m"),
            ruleContext = mapOf("a" to "budget 1000 tokens, used 500 (50%)"),
        )

        val analyzers = checkNotNull(state.provenance()).analyzers.associateBy { it.ruleId }

        assertEquals("budget 1000 tokens, used 500 (50%)", analyzers.getValue("a").context)
        assertNull(analyzers.getValue("b").context)
    }

    @Test
    fun `a rule whose dispatch completed is ok though some chunks timed out`() {
        val state = AiRuleState(
            ruleModels = mapOf("aiReview" to "m"),
            ruleSkipReasons = mapOf(
                "aiReview" to SkippedRuleInfo("aiReview", "2 chunks skipped: timeout", SkipCategory.AI_PROVIDER_ERROR),
                "aiTestGap" to SkippedRuleInfo("aiTestGap", "provider down", SkipCategory.AI_PROVIDER_ERROR),
            ),
        )

        val analyzers = checkNotNull(state.provenance()).analyzers.associateBy { it.ruleId }

        assertEquals(ProvenanceStatus.OK, analyzers.getValue("aiReview").status)
        assertEquals("2 chunks skipped: timeout", analyzers.getValue("aiReview").reason)
        assertEquals(ProvenanceStatus.FAILED, analyzers.getValue("aiTestGap").status)
        assertEquals(AiHealth(calls = 2, failed = 1), state.health())
    }

    @Test
    fun `provenance carries the review funnel`() {
        val state = AiRuleState(ruleModels = mapOf("a" to "m"), reviewFunnel = mapOf("sampled" to 3, "posted" to 1))

        assertEquals(mapOf("sampled" to 3, "posted" to 1), checkNotNull(state.provenance()).funnel)
    }

    @Test
    fun `no provenance when no AI rule ran or skipped`() {
        assertNull(AiRuleState().provenance())
    }

    @Test
    fun `track records a stage that dropped findings and skips a stage that kept them all`() =
        runTest {
            val bad = finding("bad")
            val good = finding("good")
            val stages = mutableListOf<FilterProvenance>()

            val afterGrounding = stages.track("grounding", listOf(bad, good)) { it - bad }
            val afterContradiction = stages.track("contradiction", afterGrounding) { it }

            assertEquals(listOf(good), afterContradiction)
            assertEquals(listOf(FilterProvenance("grounding", 1)), stages)
        }

    @Test
    fun `why names the rule the model and the enrichment`() {
        val withContext = finding("a").withWhy("aiReview", "gpt-4o", setOf("jira:ticket", "ci:status"))
        val diffOnly = finding("a").withWhy("aiReview", "gpt-4o", emptySet())

        assertEquals("`aiReview` on gpt-4o; context: ci:status, jira:ticket", withContext.metadata[WellKnownMetadataKeys.WHY])
        assertEquals("`aiReview` on gpt-4o; diff only", diffOnly.metadata[WellKnownMetadataKeys.WHY])
    }
}
