package io.karandash.plugins.ai.dispatch

import io.karandash.plugins.ai.core.TokenBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BudgetReservationTest {
    @Test
    fun `a spent run budget refuses a gated call`() {
        val run = TokenBudget(1_000).also { it.recordSpend(1_000) }

        assertNull(BudgetReservation.open(TokenBudget(500), run, 400))
    }

    @Test
    fun `the primary analyzer on its own share runs past a spent run budget and charges it`() {
        val run = TokenBudget(1_000).also { it.recordSpend(1_000) }
        val rule = TokenBudget(500)

        val hold = assertNotNull(BudgetReservation.open(rule, run, 400, gateRun = false))
        hold.recordSpend(300)
        hold.close()

        assertEquals(1_300, run.totalConsumed())
        assertEquals(200, rule.remaining())
    }

    @Test
    fun `the primary analyzer is still bounded by its own share`() {
        assertNull(BudgetReservation.open(TokenBudget(500), TokenBudget(10_000), 600, gateRun = false))
    }
}
