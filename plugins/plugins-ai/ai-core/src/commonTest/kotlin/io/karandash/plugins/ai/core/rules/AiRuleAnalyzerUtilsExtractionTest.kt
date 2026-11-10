package io.karandash.plugins.ai.core.rules

import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils.ExtractionStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiRuleAnalyzerUtilsExtractionTest {
    @Test
    fun `diagnoseExtraction names the strategy for each response shape`() {
        assertEquals(ExtractionStrategy.FENCE, AiRuleAnalyzerUtils.diagnoseExtraction("```json\n{\"a\":1}\n```").first)
        assertEquals(ExtractionStrategy.BALANCED_BLOCK, AiRuleAnalyzerUtils.diagnoseExtraction("Here: {\"a\":1} done").first)
        val (truncated, hint) = AiRuleAnalyzerUtils.diagnoseExtraction("{\"findings\":[{\"a\":1}")
        assertEquals(ExtractionStrategy.TRUNCATION_RECOVERY, truncated)
        assertTrue("max output token limit" in hint)
        assertEquals(ExtractionStrategy.RAW_FALLBACK, AiRuleAnalyzerUtils.diagnoseExtraction("no json here").first)
    }

    @Test
    fun `diagnoseExtraction ignores a thinking block before the fence`() {
        val (strategy, _) = AiRuleAnalyzerUtils.diagnoseExtraction("<thinking>{ draft</thinking>\n```\n[1]\n```")

        assertEquals(ExtractionStrategy.FENCE, strategy)
    }

    @Test
    fun `resolveMaxResponseTokens keeps an explicit value and scales the default for large prompts`() {
        assertEquals(700, AiRuleAnalyzerUtils.resolveMaxResponseTokens(700, 4000, "x".repeat(400_000), ""))
        assertEquals(4000, AiRuleAnalyzerUtils.resolveMaxResponseTokens(4000, 4000, "short", "prompt"))
        assertEquals(8000, AiRuleAnalyzerUtils.resolveMaxResponseTokens(4000, 4000, "x".repeat(400_000), "y"))
    }
}
