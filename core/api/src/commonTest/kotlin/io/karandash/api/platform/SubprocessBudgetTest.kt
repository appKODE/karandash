package io.karandash.api.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SubprocessBudgetTest {
    @Test
    fun `fresh budget accepts call within defaults`() {
        val budget = SubprocessBudget(maxCalls = 3, maxWallMillis = 1_000)
        val result = budget.charge(listOf("git", "status"), durationMs = 10)

        val accepted = assertIs<ChargeResult.Accepted>(result)
        assertEquals("git", accepted.head)
        assertEquals(10, accepted.durationMs)
        assertEquals(2, accepted.remainingCalls)
        assertEquals(990L, accepted.remainingWallMillis)
    }

    @Test
    fun `second over call count rejects with firstWarning only on boundary`() {
        val budget = SubprocessBudget(maxCalls = 1, maxWallMillis = 1_000)
        assertIs<ChargeResult.Accepted>(budget.charge(listOf("git"), 1))

        val first = assertIs<ChargeResult.RejectedCount>(budget.charge(listOf("git"), 1))
        assertEquals(true, first.firstWarning)
        assertEquals(2, first.used)
        assertEquals(1, first.limit)

        val second = assertIs<ChargeResult.RejectedCount>(budget.charge(listOf("git"), 1))
        assertEquals(false, second.firstWarning)
    }

    @Test
    fun `wall budget overflow is rejected independently of call count`() {
        val budget = SubprocessBudget(maxCalls = 100, maxWallMillis = 50)
        assertIs<ChargeResult.Accepted>(budget.charge(listOf("git"), 30))

        val rejected = assertIs<ChargeResult.RejectedTime>(budget.charge(listOf("git"), 30))
        assertTrue(rejected.firstWarning)
        assertEquals(60L, rejected.usedMillis)
        assertEquals(50L, rejected.limitMillis)
    }

    @Test
    fun `negative duration is clamped to zero`() {
        val budget = SubprocessBudget(maxCalls = 3, maxWallMillis = 100)
        val first = assertIs<ChargeResult.Accepted>(budget.charge(listOf("git"), -5))
        assertEquals(100L, first.remainingWallMillis)
    }

    @Test
    fun `empty command produces empty head`() {
        val budget = SubprocessBudget(maxCalls = 1, maxWallMillis = 100)
        val result = assertIs<ChargeResult.Accepted>(budget.charge(emptyList(), 1))
        assertEquals("", result.head)
    }

    @Test
    fun `unlimited budget never rejects`() {
        val budget = SubprocessBudget.UNLIMITED
        repeat(1_000) {
            val result = budget.charge(listOf("git", "grep"), durationMs = 1_000_000L)
            assertIs<ChargeResult.Accepted>(result)
        }
    }

    @Test
    fun `remaining counters never go negative once exhausted`() {
        val budget = SubprocessBudget(maxCalls = 2, maxWallMillis = 20)
        repeat(5) { budget.charge(listOf("git"), 10) }
        assertEquals(0, budget.remainingCalls)
        assertEquals(0L, budget.remainingWallMillis)
    }

    @Test
    fun `default exposes documented constants`() {
        val budget = SubprocessBudget.default()
        assertEquals(SubprocessBudget.DEFAULT_MAX_CALLS, budget.maxCalls)
        assertEquals(SubprocessBudget.DEFAULT_MAX_WALL_MILLIS, budget.maxWallMillis)
        assertEquals(30, budget.maxCalls)
        assertEquals(30_000L, budget.maxWallMillis)
    }

    @Test
    fun `concurrent charges respect call budget`() {
        val budget = SubprocessBudget(maxCalls = 25, maxWallMillis = Long.MAX_VALUE)
        var accepted = 0
        var rejected = 0

        repeat(50) {
            val result = budget.charge(listOf("git"), 1)
            when (result) {
                is ChargeResult.Accepted -> accepted++
                is ChargeResult.RejectedCount -> rejected++
                is ChargeResult.RejectedTime -> rejected++
            }
        }

        assertEquals(25, accepted)
        assertEquals(25, rejected)
        assertEquals(50, budget.callsAttempted)
    }

    @Test
    fun `count exceeded within time rejects with count reason`() {
        val budget = SubprocessBudget(maxCalls = 1, maxWallMillis = 100_000)
        assertIs<ChargeResult.Accepted>(budget.charge(listOf("git"), 1))
        val result = budget.charge(listOf("git"), 1)
        assertIs<ChargeResult.RejectedCount>(result)
    }

    @Test
    fun `time exceeded within count rejects with time reason`() {
        val budget = SubprocessBudget(maxCalls = 100, maxWallMillis = 10)
        assertIs<ChargeResult.Accepted>(budget.charge(listOf("git"), 5))
        val result = budget.charge(listOf("git"), 10)
        assertIs<ChargeResult.RejectedTime>(result)
    }

    @Test
    fun `hasRejected is false at exactly max calls and true past it`() {
        val budget = SubprocessBudget(maxCalls = 2, maxWallMillis = 100_000)
        budget.charge(listOf("git"), 1)
        budget.charge(listOf("git"), 1)
        assertEquals(0, budget.remainingCalls)
        assertEquals(false, budget.hasRejected)
        budget.charge(listOf("git"), 1)
        assertEquals(true, budget.hasRejected)
    }
}
