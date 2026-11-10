package io.karandash.plugins.ai.core

import io.karandash.models.support.KarandashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiCostEstimatorTest {
    @Test
    fun `estimateCost returns value for known model`() {
        val cost = AiCostEstimator.estimateCost("gpt-4o-mini", 1000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0)
    }

    @Test
    fun `estimateCost returns null for unknown model`() {
        val cost = AiCostEstimator.estimateCost("unknown-model-xyz", 1000, 1000)

        assertNull(cost)
    }

    @Test
    fun `estimateCost for local model returns zero`() {
        val cost = AiCostEstimator.estimateCost("llama3.2", 5000, 2000)

        assertNotNull(cost)
        assertEquals(0.0, cost)
    }

    @Test
    fun `estimateCost matches prefix for model variants`() {
        val cost = AiCostEstimator.estimateCost("gpt-4o-2024-08-06", 1000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0)
    }

    @Test
    fun `estimateCost for gemini model`() {
        val cost = AiCostEstimator.estimateCost("gemini-2.0-flash", 1000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0)
        assertTrue(cost < 0.01, "Gemini flash should be very cheap")
    }

    @Test
    fun `formatCost produces dollar formatted string`() {
        val result = AiCostEstimator.formatCost(0.0042)

        assertTrue(result.startsWith("\$"))
        assertTrue(result.contains("0042") || result.contains("42"))
    }

    @Test
    fun `formatCost for zero`() {
        val result = AiCostEstimator.formatCost(0.0)

        assertEquals("\$0.0000", result)
    }

    @Test
    fun `formatCost for larger value`() {
        val result = AiCostEstimator.formatCost(1.2345)

        assertTrue(result.startsWith("\$1."))
    }

    @Test
    fun `estimateCost calculates correctly for gpt-4o-mini`() {
        // gpt-4o-mini: input $0.00015/1K, output $0.0006/1K
        // 2000 prompt + 1000 completion = 0.00015*2 + 0.0006*1 = 0.0003 + 0.0006 = 0.0009
        val cost = AiCostEstimator.estimateCost("gpt-4o-mini", 2000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0008 && cost < 0.0010, "Expected ~0.0009 but got $cost")
    }

    @Test
    fun `estimateCost strips openrouter prefix for kimi model`() {
        val cost = AiCostEstimator.estimateCost("openrouter/moonshotai/kimi-k2.5", 1000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0, "Expected non-zero cost for kimi-k2.5")
    }

    @Test
    fun `estimateCost strips openrouter prefix for qwen model`() {
        val cost = AiCostEstimator.estimateCost("openrouter/qwen/qwen3.5-35b-a3b", 1000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0, "Expected non-zero cost for qwen3.5-35b-a3b")
    }

    @Test
    fun `estimateCost for claude-sonnet-4-6 returns non-null cost`() {
        val cost = AiCostEstimator.estimateCost("claude-sonnet-4-6", 1000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0)
    }

    @Test
    fun `estimateCost for bare model name unchanged by normalization`() {
        val cost = AiCostEstimator.estimateCost("gpt-4o", 1000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0)
    }

    @Test
    fun `estimateCost strips raw provider prefix for kimi model`() {
        val cost = AiCostEstimator.estimateCost("moonshotai/kimi-k2.5-0127", 1000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0, "Expected non-zero cost for moonshotai/kimi-k2.5-0127")
    }

    @Test
    fun `estimateCost strips raw provider prefix for qwen model`() {
        val cost = AiCostEstimator.estimateCost("qwen/qwen3.5-35b-a3b-20260224", 1000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0, "Expected non-zero cost for qwen/qwen3.5-35b-a3b-20260224")
    }

    @Test
    fun `estimateCost handles multi-segment openrouter path`() {
        val cost = AiCostEstimator.estimateCost("openrouter/qwen/qwen3.5-35b-a3b", 1000, 1000)

        assertNotNull(cost)
        assertTrue(cost > 0.0, "Expected non-zero cost for openrouter/qwen/qwen3.5-35b-a3b")
    }

    @Test
    fun `Claude 5_5 models are priced per the published per-MTok rates`() {
        assertCost(4.0 + 20.0, "claude-opus-5-5")
        assertCost(2.0 + 10.0, "claude-sonnet-5-5")
        assertCost(0.10 + 0.50, "claude-haiku-5-5")
    }

    @Test
    fun `OpenRouter ids with a dotted Claude version match the dashed row`() {
        assertCost(0.10 + 0.50, "openrouter/anthropic/claude-haiku-5.5")
        assertCost(2.0 + 10.0, "anthropic/claude-sonnet-5.5")
    }

    @Test
    fun `DeepSeek V4 and Kimi K2_7 have their own rows`() {
        assertCost(0.09 + 0.18, "deepseek/deepseek-v4-flash")
        assertCost(0.6 + 3.5, "moonshotai/kimi-k2.7-code")
    }

    @Test
    fun `Claude 4_x rows carry current prices and dated ids match by prefix`() {
        assertCost(5.0 + 25.0, "claude-opus-4-6")
        assertCost(1.0 + 5.0, "claude-haiku-4-5-20251001")
        assertCost(3.0 + 15.0, "claude-sonnet-4-20250514")
        assertNull(AiCostEstimator.estimateCost("claude-haiku-4-6", 1000, 1000), "claude-haiku-4-6 is not a real model")
    }

    @Test
    fun `longest prefix wins over table order`() {
        assertCost(0.15 + 0.6, "gpt-4o-mini-2024-07-18")
    }

    @Test
    fun `ai pricing entries are USD per MTok and override the defaults`() {
        val estimator = AiCostEstimator.fromConfig(
            mapOf("claude-sonnet-5-5" to ModelPrice(input = 1.0, output = 3.0), "in-house" to ModelPrice(0.5, 0.5)),
        )

        assertEquals(4.0, estimator.estimateCost("claude-sonnet-5-5", MTOK, MTOK) ?: -1.0, 1e-9)
        assertEquals(1.0, estimator.estimateCost("in-house-v2", MTOK, MTOK) ?: -1.0, 1e-9)
    }

    @Test
    fun `ai pricing parses from the ai block with model ids kept verbatim`() {
        val cfg = AiPluginConfig.fromPluginConfig(
            KarandashJson.Default.parseToJsonElement(
                """{"pricing": {"claude-sonnet-5-5": {"input": 2.5, "output": 12}}}""",
            ),
        )

        assertEquals(mapOf("claude-sonnet-5-5" to ModelPrice(2.5, 12.0)), cfg.pricing)
    }

    private fun assertCost(
        expectedUsdPerMtokPair: Double,
        model: String,
    ) {
        val cost = AiCostEstimator.estimateCost(model, MTOK, MTOK)
        assertNotNull(cost, "no price for $model")
        assertEquals(expectedUsdPerMtokPair, cost, 1e-9, model)
    }

    @Test
    fun `anthropic cache writes cost 1_25x and reads 0_1x of the input price`() {
        val estimator = AiCostEstimator.fromConfig(mapOf("claude-test" to ModelPrice(input = 10.0, output = 0.0)))
        val cost = assertNotNull(estimator.estimateCost("claude-test", MTOK, 0, cacheReadTokens = 400_000, cacheWriteTokens = 400_000))
        // 0.2M plain at 10 + 0.4M written at 12.5 + 0.4M read at 1
        assertEquals(2.0 + 5.0 + 0.4, cost, 1e-9)
    }

    @Test
    fun `cached tokens of other providers are priced as plain input`() {
        val estimator = AiCostEstimator.fromConfig(mapOf("gpt-test" to ModelPrice(input = 10.0, output = 0.0)))
        assertEquals(10.0, assertNotNull(estimator.estimateCost("gpt-test", MTOK, 0, cacheReadTokens = MTOK)), 1e-9)
    }

    @Test
    fun `a 1h prompt cache TTL prices anthropic cache writes at 2x`() {
        val estimator = AiCostEstimator.fromConfig(mapOf("claude-test" to ModelPrice(input = 10.0, output = 0.0)), promptCacheTtl = "1h")
        assertEquals(20.0, assertNotNull(estimator.estimateCost("claude-test", MTOK, 0, cacheWriteTokens = MTOK)), 1e-9)
    }

    @Test
    fun `Fable and Opus 5_5 cache reads use their published per-MTok rates`() {
        val estimator = AiCostEstimator()
        // Fable 5.1 $10 input, reads $0.25; Fable 5 reads at the standard 0.1x ($1); Opus 5.5 $4 input, reads $0.20
        assertEquals(0.25, assertNotNull(estimator.estimateCost("claude-fable-5-1", MTOK, 0, cacheReadTokens = MTOK)), 1e-9)
        assertEquals(1.0, assertNotNull(estimator.estimateCost("claude-fable-5", MTOK, 0, cacheReadTokens = MTOK)), 1e-9)
        assertEquals(0.20, assertNotNull(estimator.estimateCost("claude-opus-5-5", MTOK, 0, cacheReadTokens = MTOK)), 1e-9)
        assertEquals(50.0, assertNotNull(estimator.estimateCost("claude-fable-5-1", 0, MTOK)), 1e-9)
    }

    @Test
    fun `OpenAI gpt-4o cached input is half price and reports no writes`() {
        val estimator = AiCostEstimator()
        // gpt-4o $2.50 input: 0.5M plain + 0.5M cached at $1.25; a write count is ignored
        val cost = estimator.estimateCost("gpt-4o-2024-08-06", MTOK, 0, cacheReadTokens = 500_000, cacheWriteTokens = 500_000)
        assertEquals(1.25 + 0.625, assertNotNull(cost), 1e-9)
    }

    private companion object {
        const val MTOK = 1_000_000
    }
}
