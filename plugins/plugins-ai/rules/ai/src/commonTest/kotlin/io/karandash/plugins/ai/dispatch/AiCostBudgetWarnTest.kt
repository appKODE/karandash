package io.karandash.plugins.ai.dispatch

import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.core.RuleGate
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.config.CostBudgetConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ai.costBudget.onBudgetExceeded: warn` keeps the rule running and logs a warn naming the exhausted budget. */
class AiCostBudgetWarnTest {
    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val mr =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = "d",
            sourceBranch = "feature/x",
            targetBranch = "main",
            author = User("u1", "dev", "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        )

    private fun exhausted() = TokenBudget(100).also { it.recordSpend(100) }

    @Test
    fun `warn action continues and logs a warning`() {
        val log = WarnLogger()
        val evaluator = AiSkipEvaluator(logger = log, ruleGate = RuleGate())

        val result = evaluator.evaluateCostBudget(
            "review-comments",
            mr,
            CostBudgetConfig(maxTokensPerRun = 100, onBudgetExceeded = "warn"),
            exhausted()
        )

        assertEquals(SkipResult.Continue, result)
        assertTrue(log.warnings.any { "review-comments" in it && "cost budget exhausted" in it }, log.warnings.toString())
    }

    @Test
    fun `budget not reached logs no warning`() {
        val log = WarnLogger()
        val evaluator = AiSkipEvaluator(logger = log, ruleGate = RuleGate())

        evaluator.evaluateCostBudget(
            "review-comments",
            mr,
            CostBudgetConfig(maxTokensPerRun = 100, onBudgetExceeded = "warn"),
            TokenBudget(100)
        )

        assertEquals(emptyList(), log.warnings)
    }
}
