package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.rules.BudgetAllocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenBudgetFileSplitTest {
    private fun scored(
        path: String,
        score: Double
    ): FilePriorityScorer.ScoredFile =
        FilePriorityScorer.ScoredFile(
            change = FileChange(path = path, changeType = ChangeType.MODIFIED),
            score = score,
            breakdown = FilePriorityScorer.ScoreBreakdown(0.0, 0.0, 0.0, 0.0),
        )

    @Test
    fun `empty input returns empty output`() {
        val result = TokenBudget.splitAcrossFiles(
            emptyList(),
            totalBudget = 10_000,
            maxPerFile = 2_000,
            allocation = BudgetAllocation.PROPORTIONAL,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `proportional allocates more to higher score`() {
        val files = listOf(scored("a", 0.9), scored("b", 0.1))
        val alloc = TokenBudget.splitAcrossFiles(
            files,
            totalBudget = 10_000,
            maxPerFile = 10_000,
            allocation = BudgetAllocation.PROPORTIONAL,
        )
        assertTrue(alloc.first().budget > alloc.last().budget)
        assertEquals(10_000, alloc.sumOf { it.budget })
    }

    @Test
    fun `equal splits budget evenly`() {
        val files = listOf(scored("a", 0.9), scored("b", 0.1), scored("c", 0.5))
        val alloc = TokenBudget.splitAcrossFiles(
            files,
            totalBudget = 9_000,
            maxPerFile = 10_000,
            allocation = BudgetAllocation.EQUAL,
        )
        assertEquals(3_000, alloc[0].budget)
        assertEquals(3_000, alloc[1].budget)
        assertEquals(3_000, alloc[2].budget)
    }

    @Test
    fun `floor is enforced when proportional would go below`() {
        val files = listOf(scored("a", 0.99), scored("b", 0.01))
        val alloc = TokenBudget.splitAcrossFiles(
            files,
            totalBudget = 10_000,
            maxPerFile = 10_000,
            allocation = BudgetAllocation.PROPORTIONAL,
        )
        assertTrue(alloc.all { it.budget >= TokenBudget.MIN_FILE_BUDGET })
    }

    @Test
    fun `cap is enforced`() {
        val files = listOf(scored("a", 0.9), scored("b", 0.1))
        val alloc = TokenBudget.splitAcrossFiles(
            files,
            totalBudget = 100_000,
            maxPerFile = 1_000,
            allocation = BudgetAllocation.PROPORTIONAL,
        )
        assertTrue(alloc.all { it.budget <= 1_000 })
    }

    @Test
    fun `surplus redistributes to highest-priority file with headroom`() {
        val files = listOf(scored("high", 0.8), scored("low", 0.2))
        // Initial proportional: high gets 80, low gets 20. Both clamped up to 200 floor.
        // 200 + 200 = 400 used; surplus 600. Maxed at 500 per file.
        val alloc = TokenBudget.splitAcrossFiles(
            files,
            totalBudget = 1_000,
            maxPerFile = 500,
            allocation = BudgetAllocation.PROPORTIONAL,
        )
        // Both files end up at 500 after surplus redistribution.
        assertEquals(500, alloc[0].budget)
        assertEquals(500, alloc[1].budget)
    }

    @Test
    fun `underflow triggers fallback when total lt floor times N`() {
        val files = listOf(scored("a", 0.8), scored("b", 0.2))
        val alloc = TokenBudget.splitAcrossFiles(
            files,
            totalBudget = 300,
            maxPerFile = 2_000,
            allocation = BudgetAllocation.PROPORTIONAL,
        )
        // 300 / 2 = 150 per file - below normal floor but above UNDERFLOW_FLOOR.
        assertEquals(150, alloc[0].budget)
        assertEquals(150, alloc[1].budget)
    }

    @Test
    fun `underflow respects underflow floor for very small budgets`() {
        val files = listOf(scored("a", 0.5), scored("b", 0.5), scored("c", 0.5))
        val alloc = TokenBudget.splitAcrossFiles(
            files,
            totalBudget = 30,
            maxPerFile = 100,
            allocation = BudgetAllocation.PROPORTIONAL,
        )
        // 30 / 3 = 10 → clamped up to UNDERFLOW_FLOOR (50).
        alloc.forEach { assertEquals(TokenBudget.UNDERFLOW_FLOOR, it.budget) }
    }

    @Test
    fun `zero score falls back to equal within proportional`() {
        val files = listOf(scored("a", 0.0), scored("b", 0.0), scored("c", 0.0))
        val alloc = TokenBudget.splitAcrossFiles(
            files,
            totalBudget = 6_000,
            maxPerFile = 10_000,
            allocation = BudgetAllocation.PROPORTIONAL,
        )
        // All zero score → equal fallback then clamped to floor.
        assertTrue(alloc.all { it.budget == 2_000 })
    }

    @Test
    fun `single file receives full budget within cap`() {
        val files = listOf(scored("a", 1.0))
        val alloc = TokenBudget.splitAcrossFiles(
            files,
            totalBudget = 5_000,
            maxPerFile = 3_000,
            allocation = BudgetAllocation.PROPORTIONAL,
        )
        assertEquals(3_000, alloc.single().budget)
    }

    @Test
    fun `negative totalBudget rejected`() {
        val files = listOf(scored("a", 1.0))
        assertFailsWith<IllegalArgumentException> {
            TokenBudget.splitAcrossFiles(
                files,
                totalBudget = -1,
                maxPerFile = 1_000,
                allocation = BudgetAllocation.PROPORTIONAL,
            )
        }
    }

    @Test
    fun `zero maxPerFile gives every file a zero budget`() {
        val out = TokenBudget.splitAcrossFiles(
            listOf(scored("a", 1.0)),
            totalBudget = 1_000,
            maxPerFile = 0,
            allocation = BudgetAllocation.PROPORTIONAL,
        )
        assertEquals(0, out.single().budget)
    }

    @Test
    fun `no file gets more than it needs and the rest goes to the others`() {
        val out = TokenBudget.splitAcrossFiles(
            listOf(scored("small", 9.0), scored("big", 1.0)),
            totalBudget = 1_000,
            maxPerFile = 1_000,
            allocation = BudgetAllocation.PROPORTIONAL,
            needOf = { if (it.change.path == "small") 300 else 700 },
        )
        assertEquals(listOf(300, 700), out.map { it.budget })
    }
}

private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
    try {
        block()
        throw AssertionError("Expected ${T::class.simpleName} but no exception was thrown")
    } catch (e: Throwable) {
        if (e !is T) throw AssertionError("Expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
    }
}
