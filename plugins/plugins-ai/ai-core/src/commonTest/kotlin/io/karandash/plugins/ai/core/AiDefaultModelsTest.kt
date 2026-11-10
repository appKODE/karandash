package io.karandash.plugins.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Current default models with pricing rows, and `cheapFor` falls back to `ai.model`. */
class AiDefaultModelsTest {
    @Test
    fun `every default model has a pricing row`() {
        for (model in listOf(AiDefaultModels.OPENAI, AiDefaultModels.GEMINI, AiDefaultModels.ANTHROPIC, AiDefaultModels.ANTHROPIC_CHEAP)) {
            assertNotNull(AiCostEstimator.estimateCost(model, 1000, 1000), model)
        }
    }

    @Test
    fun `gpt-5 and gemini 2_5 and 3 families are priced`() {
        val cases = mapOf(
            "gpt-5.4-mini" to 0.00075 + 0.0045,
            "gpt-5-mini-2025-08-07" to 0.00025 + 0.002,
            "gpt-5.5" to 0.005 + 0.030,
            "gemini-2.5-flash" to 0.0003 + 0.0025,
            "gemini-2.5-pro" to 0.00125 + 0.010,
            "gemini-3.5-flash-lite" to 0.0003 + 0.0025,
        )
        for ((model, expected) in cases) {
            assertEquals(expected, checkNotNull(AiCostEstimator.estimateCost(model, 1000, 1000)) { model }, 1e-9, model)
        }
    }

    @Test
    fun `cheapFor an unknown provider uses the configured model`() {
        assertEquals("my-model", AiDefaultModels.cheapFor("custom", "my-model"))
        assertEquals("llama3.2", AiDefaultModels.cheapFor("ollama", "llama3.2"))
        assertEquals(AiDefaultModels.OPENAI, AiDefaultModels.cheapFor("openai", "gpt-5.5"))
        assertEquals(AiDefaultModels.ANTHROPIC_CHEAP, AiDefaultModels.cheapFor("anthropic", "claude-opus-5-5"))
        assertEquals(AiDefaultModels.GENERIC, AiDefaultModels.cheapFor("custom", null))
    }
}
