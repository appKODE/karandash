package io.karandash.plugins.ai.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiCostBudgetResolverTest {
    @Test
    fun `default budget returned when no branch budgets configured`() {
        val config = CostBudgetConfig(maxTokensPerRun = 30000)
        assertEquals(30000, AiCostBudgetResolver.resolveTokenBudget("feature/my-branch", config))
    }

    @Test
    fun `branch-specific budget overrides default`() {
        val config = CostBudgetConfig(
            maxTokensPerRun = 30000,
            branchBudgets = listOf(BranchBudget(pattern = "main", maxTokensPerRun = 100000)),
        )
        assertEquals(100000, AiCostBudgetResolver.resolveTokenBudget("main", config))
    }

    @Test
    fun `non-matching branch uses default budget`() {
        val config = CostBudgetConfig(
            maxTokensPerRun = 30000,
            branchBudgets = listOf(BranchBudget(pattern = "main", maxTokensPerRun = 100000)),
        )
        assertEquals(30000, AiCostBudgetResolver.resolveTokenBudget("feature/other", config))
    }

    @Test
    fun `first matching branch budget wins`() {
        val config = CostBudgetConfig(
            maxTokensPerRun = 10000,
            branchBudgets = listOf(
                BranchBudget(pattern = "release", maxTokensPerRun = 80000),
                BranchBudget(pattern = "release/.*", maxTokensPerRun = 999),
            ),
        )
        assertEquals(80000, AiCostBudgetResolver.resolveTokenBudget("release/1.0", config))
    }

    @Test
    fun `branch pattern matched as substring`() {
        val config = CostBudgetConfig(
            maxTokensPerRun = 10000,
            branchBudgets = listOf(BranchBudget(pattern = "hotfix", maxTokensPerRun = 60000)),
        )
        assertEquals(60000, AiCostBudgetResolver.resolveTokenBudget("hotfix/critical-fix", config))
    }

    @Test
    fun `branch pattern matched as regex`() {
        val config = CostBudgetConfig(
            maxTokensPerRun = 10000,
            branchBudgets = listOf(BranchBudget(pattern = "^release/\\d+\\.\\d+$", maxTokensPerRun = 75000)),
        )
        assertEquals(75000, AiCostBudgetResolver.resolveTokenBudget("release/2.0", config))
    }

    @Test
    fun `invalid regex pattern falls back to substring match`() {
        val config = CostBudgetConfig(
            maxTokensPerRun = 10000,
            branchBudgets = listOf(BranchBudget(pattern = "[invalid", maxTokensPerRun = 50000)),
        )
        val result = AiCostBudgetResolver.resolveTokenBudget("some-branch", config)
        assertEquals(10000, result)
    }

    @Test
    fun `isOverBudget returns true when cost exceeds limit`() {
        val config = CostBudgetConfig(maxCostPerRunUsd = 1.0)
        assertTrue(AiCostBudgetResolver.isOverBudget(1.5, config))
    }

    @Test
    fun `isOverBudget returns true when cost equals limit`() {
        val config = CostBudgetConfig(maxCostPerRunUsd = 1.0)
        assertTrue(AiCostBudgetResolver.isOverBudget(1.0, config))
    }

    @Test
    fun `isOverBudget returns false when cost is below limit`() {
        val config = CostBudgetConfig(maxCostPerRunUsd = 1.0)
        assertFalse(AiCostBudgetResolver.isOverBudget(0.5, config))
    }

    @Test
    fun `zero maxCostPerRunUsd means no dollar limit`() {
        val config = CostBudgetConfig(maxCostPerRunUsd = 0.0)
        assertFalse(AiCostBudgetResolver.isOverBudget(999.0, config))
    }

    @Test
    fun `negative maxCostPerRunUsd means no dollar limit`() {
        val config = CostBudgetConfig(maxCostPerRunUsd = -1.0)
        assertFalse(AiCostBudgetResolver.isOverBudget(100.0, config))
    }

    @Test
    fun `onBudgetExceeded returns SKIP_REMAINING for skip-remaining`() {
        val config = CostBudgetConfig(onBudgetExceeded = "skip-remaining")
        assertEquals(BudgetAction.SKIP_REMAINING, AiCostBudgetResolver.onBudgetExceeded(config))
    }

    @Test
    fun `onBudgetExceeded returns WARN for warn`() {
        val config = CostBudgetConfig(onBudgetExceeded = "warn")
        assertEquals(BudgetAction.WARN, AiCostBudgetResolver.onBudgetExceeded(config))
    }

    @Test
    fun `onBudgetExceeded falls back to SKIP_REMAINING with a warning for an unknown value`() {
        val warnings = mutableListOf<String>()
        val config = CostBudgetConfig(onBudgetExceeded = "unknown-action")
        assertEquals(BudgetAction.SKIP_REMAINING, AiCostBudgetResolver.onBudgetExceeded(config) { warnings += it })
        assertEquals(1, warnings.size)
        assertTrue("unknown-action" in warnings.single(), warnings.toString())
    }

    @Test
    fun `onBudgetExceeded matches the value case-insensitively`() {
        val warnings = mutableListOf<String>()
        assertEquals(
            BudgetAction.WARN,
            AiCostBudgetResolver.onBudgetExceeded(CostBudgetConfig(onBudgetExceeded = "WARN")) { warnings += it }
        )
        assertEquals(emptyList(), warnings)
    }
}
