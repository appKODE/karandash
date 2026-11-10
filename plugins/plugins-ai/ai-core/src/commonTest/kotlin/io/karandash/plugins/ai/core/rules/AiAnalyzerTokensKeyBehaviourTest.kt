package io.karandash.plugins.ai.core.rules

import io.karandash.plugins.ai.core.config.AiAnalyzerTokensConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/** `ai.analyzer.tokens.charsPerTokenEstimate`: a smaller estimate means more tokens per prompt and a bigger auto-scaled response. */
class AiAnalyzerTokensKeyBehaviourTest {
    private fun scaled(tokens: AiAnalyzerTokensConfig) =
        AiRuleAnalyzerUtils.resolveMaxResponseTokens(
            configuredMax = 100,
            defaultMax = 100,
            systemPrompt = "x".repeat(40_000),
            userPrompt = "u",
            tokens = tokens,
        )

    @Test
    fun `analyzer tokens charsPerTokenEstimate scales the response budget with the prompt estimate`() {
        val wide = scaled(AiAnalyzerTokensConfig(minResponse = 500, maxAutoResponse = 32_000, charsPerTokenEstimate = 8))
        val narrow = scaled(AiAnalyzerTokensConfig(minResponse = 500, maxAutoResponse = 32_000, charsPerTokenEstimate = 2))
        assertTrue(narrow > wide, "narrow=$narrow wide=$wide")
    }
}
