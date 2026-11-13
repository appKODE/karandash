package io.karandash.plugins.ai

import io.karandash.plugins.ai.core.config.CostEstimateConfig
import io.karandash.plugins.ai.dispatch.RuleCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiRuleCostLoggerTest {
    @Test
    fun `budget skipped rule is reported as skipped not as ran with no findings`() {
        val coverage = RuleCoverage(files = setOf("A.kt"), reviewed = emptySet(), chunksSkipped = 1)

        val line = AiRuleCostLogger.ruleCostLine("risk-scoring", tokens = null, skipReason = null, timingMs = 5, coverage = coverage)

        assertEquals("  risk-scoring: skipped (budget exhausted)", line)
    }

    @Test
    fun `rule with a timing and no tokens is reported as ok`() {
        val coverage = RuleCoverage(files = setOf("A.kt"), reviewed = setOf("A.kt"))

        val line = AiRuleCostLogger.ruleCostLine("risk-scoring", tokens = null, skipReason = null, timingMs = 1500, coverage = coverage)

        assertEquals("  risk-scoring: ok, no tokens reported (1s 500ms)", line)
    }

    @Test
    fun `rule with no timing no tokens and no reason is reported as not run`() {
        val line = AiRuleCostLogger.ruleCostLine("risk-scoring", tokens = null, skipReason = null, timingMs = null, coverage = null)

        assertEquals("  risk-scoring: not run", line)
    }

    @Test
    fun `rule served from the cache is reported as from cache`() {
        val line =
            AiRuleCostLogger.ruleCostLine(
                "risk-scoring",
                tokens = null,
                skipReason = null,
                timingMs = null,
                coverage = null,
                fromCache = true
            )

        assertEquals("  risk-scoring: from cache", line)
    }

    @Test
    fun `provider error reason is reported as skipped with the reason`() {
        val line =
            AiRuleCostLogger.ruleCostLine(
                "mr-summary",
                tokens = null,
                skipReason = "provider error: timed out",
                timingMs = null,
                coverage = null
            )

        assertEquals("  mr-summary: failed (provider error: timed out)", line)
    }

    @Test
    fun `a rule that ran names the diff and context the budget left out`() {
        val coverage = RuleCoverage(
            files = setOf("A.kt", "B.kt"),
            reviewed = setOf("A.kt"),
            dropped = setOf("B.kt"),
            contextDropped = setOf("ci-context"),
        )

        val line = AiRuleCostLogger.ruleCostLine(
            "risk-scoring",
            tokens = "10 tokens",
            skipReason = null,
            timingMs = null,
            coverage = coverage
        )

        assertEquals("  risk-scoring: ok, 10 tokens, left out: B.kt, context left out: ci-context", line)
    }

    @Test
    fun `a rule that left nothing out prints no left out note`() {
        val coverage = RuleCoverage(files = setOf("A.kt"), reviewed = setOf("A.kt"))

        val line = AiRuleCostLogger.ruleCostLine(
            "risk-scoring",
            tokens = "10 tokens",
            skipReason = null,
            timingMs = null,
            coverage = coverage
        )

        assertEquals("  risk-scoring: ok, 10 tokens", line)
    }

    @Test
    fun `run coverage carries what the budget left out and not what another rule reviewed`() {
        val first = RuleCoverage(
            files = setOf("A.kt", "B.kt", "C.kt"),
            reviewed = setOf("A.kt"),
            dropped = setOf("B.kt", "C.kt (2 of 5 hunks)"),
            contextDropped = setOf("ci-context"),
        )
        val second = RuleCoverage(files = setOf("A.kt", "B.kt"), reviewed = setOf("B.kt"), contextDropped = setOf("crossFileContext"))

        val run = assertNotNull(RuleCoverage.merge(listOf(first, second)))

        assertEquals(listOf("C.kt (2 of 5 hunks)"), run.dropped)
        assertEquals(listOf("ci-context", "crossFileContext"), run.contextDropped)
    }

    @Test
    fun `alert fires only above a positive threshold with sendAlert on`() {
        val config = CostEstimateConfig(alertThreshold = 1.0)
        val alert = assertNotNull(AiRuleCostLogger.alertMessage(2.5, config))
        assertTrue("\$2.5000 > \$1.0000" in alert)
        assertNull(AiRuleCostLogger.alertMessage(0.5, config))
        assertNull(AiRuleCostLogger.alertMessage(2.5, config.copy(sendAlert = false)))
        assertNull(AiRuleCostLogger.alertMessage(2.5, CostEstimateConfig()))
        assertNull(AiRuleCostLogger.alertMessage(2.5, null))
    }

    @Test
    fun `the run total adds the extra samples the run budget never charged`() {
        val snapshot = AiRuleState(
            ruleTokenCounts = mapOf("review-comments" to 1500),
            sampleTokens = mapOf("review-comments" to 500),
            auxiliaryTokens = mapOf("verifier" to 100),
        )

        assertEquals(1600, AiRuleCostLogger.totalTokens(budgetConsumed = 1000, snapshot = snapshot))
        assertEquals(1600, AiRuleCostLogger.totalTokens(budgetConsumed = 0, snapshot = snapshot))
    }
}
