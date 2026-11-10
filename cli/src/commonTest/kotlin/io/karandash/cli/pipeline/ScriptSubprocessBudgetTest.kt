package io.karandash.cli.pipeline

import io.karandash.api.platform.ChargeResult
import io.karandash.config.Config
import io.karandash.config.ScriptEntryConfig
import io.karandash.config.TimeoutConfig
import io.karandash.duration.KarandashDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptSubprocessBudgetTest {
    private val scripts = List(100) { ScriptEntryConfig(path = "s$it.mjs") }

    @Test
    fun `the default budget never trims existing script users`() {
        val budget = scriptSubprocessBudget(Config(scripts = scripts))
        repeat(100) {
            assertIs<ChargeResult.Accepted>(budget.charge(listOf("node"), 20_000))
        }
        assertTrue(budget.remainingCalls > 0)
        assertTrue(budget.remainingWallMillis > 0)
    }

    @Test
    fun `an explicit timeouts scripts value enforces the wall budget`() {
        val config = Config(scripts = scripts, timeouts = TimeoutConfig(scripts = KarandashDuration.ofSeconds(5)))
        val budget = scriptSubprocessBudget(config)
        assertEquals(5_000L, budget.maxWallMillis)
        assertIs<ChargeResult.Accepted>(budget.charge(listOf("node"), 3_000))
        assertIs<ChargeResult.RejectedTime>(budget.charge(listOf("node"), 3_000))
        assertEquals(0L, budget.remainingWallMillis)
    }
}
