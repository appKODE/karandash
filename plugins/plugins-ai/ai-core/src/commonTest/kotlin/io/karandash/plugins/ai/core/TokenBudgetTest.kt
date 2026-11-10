package io.karandash.plugins.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenBudgetTest {
    @Test
    fun `recordSpend accumulates consumed tokens`() {
        val budget = TokenBudget(1000)

        budget.recordSpend(300)
        budget.recordSpend(200)

        assertEquals(500, budget.totalConsumed())
    }

    @Test
    fun `canSpend returns true when tokens fit within budget`() {
        val budget = TokenBudget(1000)
        budget.recordSpend(400)

        assertTrue(budget.canSpend(600))
    }

    @Test
    fun `canSpend returns false when estimated spend would exceed budget`() {
        val budget = TokenBudget(1000)
        budget.recordSpend(800)

        assertFalse(budget.canSpend(201))
    }

    @Test
    fun `isExhausted returns false when consumed is below max`() {
        val budget = TokenBudget(1000)
        budget.recordSpend(999)

        assertFalse(budget.isExhausted())
    }

    @Test
    fun `isExhausted returns true when consumed equals maxTokens`() {
        val budget = TokenBudget(500)
        budget.recordSpend(500)

        assertTrue(budget.isExhausted())
    }

    @Test
    fun `isExhausted returns true when consumed exceeds maxTokens`() {
        val budget = TokenBudget(500)
        budget.recordSpend(600)

        assertTrue(budget.isExhausted())
    }

    @Test
    fun `remaining decreases as tokens are spent`() {
        val budget = TokenBudget(1000)

        assertEquals(1000, budget.remaining())
        budget.recordSpend(300)
        assertEquals(700, budget.remaining())
        budget.recordSpend(700)
        assertEquals(0, budget.remaining())
    }

    @Test
    fun `remaining never goes below zero`() {
        val budget = TokenBudget(100)
        budget.recordSpend(200)

        assertEquals(0, budget.remaining())
    }

    @Test
    fun `totalConsumed reflects all spends`() {
        val budget = TokenBudget(10000)

        budget.recordSpend(100)
        budget.recordSpend(250)
        budget.recordSpend(50)

        assertEquals(400, budget.totalConsumed())
    }

    @Test
    fun `canSpend returns true at exact boundary`() {
        val budget = TokenBudget(1000)
        budget.recordSpend(500)

        assertTrue(budget.canSpend(500))
    }
}
