package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.config.AiBudgetConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `ai.context.budget` resolution, the per-model calibration, the coverage summary and the split-on-length halves. */
class ContextBudgetTest {
    @Test
    fun `parse reads auto and missing as null and a positive count as itself`() {
        assertNull(ContextBudget.parse(null))
        assertNull(ContextBudget.parse(JsonPrimitive("auto")))
        assertEquals(12_000, ContextBudget.parse(JsonPrimitive(12_000)))
        assertEquals(12_000, ContextBudget.parse(JsonPrimitive("12000")))
    }

    @Test
    fun `context budget is read from ai context and kept out of the template variables`() {
        val cfg = AiPluginConfig.fromPluginConfig(
            JsonObject(mapOf("context" to JsonObject(mapOf("budget" to JsonPrimitive(48_000), "team" to JsonPrimitive("core"))))),
        )

        assertEquals(48_000, cfg.contextBudget)
        assertEquals(mapOf("team" to "core"), cfg.context)
    }

    @Test
    fun `parse warns and falls back to auto on a bad value`() {
        val warnings = mutableListOf<String>()
        val logger = RecordingLogger(warnings)
        assertNull(ContextBudget.parse(JsonPrimitive(-5), logger))
        assertNull(ContextBudget.parse(JsonPrimitive("lots"), logger))
        assertEquals(2, warnings.size)
    }

    @Test
    fun `auto is a quarter of the window capped at the maximum and by the cost cap`() {
        assertEquals(50_000, ContextBudget.auto("claude-sonnet-4-5"))
        assertEquals(32_000, ContextBudget.auto("openrouter/openai/gpt-4o"))
        assertEquals(ContextBudget.AUTO_MAX_TOKENS, ContextBudget.auto("gemini-2.5-pro"))
        assertEquals(32_000, ContextBudget.auto("unknown-model"))
        assertEquals(10_000, ContextBudget.auto("claude-sonnet-4-5", costCapTokens = 10_000))
    }

    @Test
    fun `cost cap tokens come from the run cap and the input price`() {
        assertEquals(20_000, ContextBudget.costCapTokens(0.06, 0.003))
        assertNull(ContextBudget.costCapTokens(0.0, 0.003))
        assertNull(ContextBudget.costCapTokens(1.0, null))
    }

    @Test
    fun `resolve prefers the setting and keeps an explicit max-input-tokens as a ceiling`() {
        val open = AiBudgetConfig()
        val capped = AiBudgetConfig(maxInputTokens = 8_000)
        assertEquals(20_000, ContextBudget.resolve(20_000, open, "claude-x"))
        assertEquals(8_000, ContextBudget.resolve(20_000, capped, "claude-x"))
        assertEquals(8_000, ContextBudget.resolve(null, capped, "claude-x"))
        assertEquals(50_000, ContextBudget.resolve(null, open, "claude-x"))
    }

    @Test
    fun `calibration divides the budget by the observed ratio per model and clamps it`() {
        val calibration = PromptTokenCalibration()
        assertEquals(1_000, calibration.adjust("m", 1_000), "no data yet")
        calibration.record("m", actualTokens = 150, estimatedTokens = 100)
        calibration.record("m", actualTokens = 0, estimatedTokens = 100)
        assertEquals(1.5, calibration.ratio("m"))
        assertEquals(666, calibration.adjust("m", 1_000))
        assertEquals(1_000, calibration.adjust("other", 1_000), "ratios are per model")
        calibration.record("x", actualTokens = 1_000, estimatedTokens = 10)
        assertEquals(PromptTokenCalibration.MAX_RATIO, calibration.ratio("x"))
    }

    @Test
    fun `coverage reports used percent and per tier tokens`() {
        val t2 = listOf(PromptPart("defs", "d".repeat(400), tier = ContextTier.SYMBOL_DEFINITIONS))
        val plan = PromptTierPlanner.plan(1_000, listOf(PromptPart("sys", "s".repeat(400))), t2, 300) as TierPlan.Fits
        val coverage = ContextCoverage.of(plan, budget = 1_000, diffTokensWanted = 300)

        assertEquals(500, coverage.used)
        assertEquals(50, coverage.usedPercent)
        assertEquals(listOf(TierUsage(ContextTier.SYMBOL_DEFINITIONS, kept = 100)), coverage.tiers)
        assertTrue(coverage.describe().contains("symbol_definitions kept 100"), coverage.describe())
    }

    @Test
    fun `halveChanges splits by file then by hunk and gives up on one hunk`() {
        val a = change("a.kt", "@@ -1 +1 @@\n-a\n+b")
        val b = change("b.kt", "@@ -1 +1 @@\n-c\n+d")
        assertEquals(listOf(a) to listOf(b), halveChanges(listOf(a, b)))

        val two = change("c.kt", "@@ -1 +1 @@\n-a\n+b\n@@ -9 +9 @@\n-c\n+d")
        val (first, second) = checkNotNull(halveChanges(listOf(two)))
        assertEquals("@@ -1 +1 @@\n-a\n+b", first.single().diffContent)
        assertEquals("@@ -9 +9 @@\n-c\n+d", second.single().diffContent)

        assertNull(halveChanges(listOf(a)))
    }

    @Test
    fun `hunksPast counts hunks that end beyond the cut`() {
        val two = change("c.kt", "@@ -1 +1 @@\n-a\n+b\n@@ -9 +9 @@\n-c\n+d")
        assertEquals(0, hunksPast(listOf(two), 1_000))
        assertEquals(1, hunksPast(listOf(two), 20))
        assertEquals(2, hunksPast(listOf(two), 5))
    }

    private fun change(
        path: String,
        diff: String,
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, diffContent = diff)

    private class RecordingLogger(
        private val warnings: MutableList<String>,
    ) : io.karandash.sdk.Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }
}
