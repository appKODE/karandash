package io.karandash.plugins.ai.core

import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.config.AiAnalyzerTokensConfig
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.sdk.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

/** The formerly dead `ai:` keys reach the code that acts on them. */
class AiDeadKeysTest {
    private fun json(text: String) = KarandashJson.Default.parseToJsonElement(text)

    @Test
    fun `consensus models and strategy reach the plugin config`() {
        val cfg = AiPluginConfig.fromPluginConfig(
            AiPluginConfig.topLevelAiBlockToPluginConfigElement(
                json("""{"consensus":{"models":["a","b"],"strategy":"union"}}"""),
            ),
        )

        assertEquals(listOf("a", "b"), cfg.consensusModels)
        assertEquals("union", cfg.consensusStrategy)
    }

    @Test
    fun `consensusModels long form wins over the consensus block`() {
        val cfg = AiPluginConfig.fromPluginConfig(
            AiPluginConfig.topLevelAiBlockToPluginConfigElement(
                json("""{"consensusModels":["x"],"consensus":{"models":["a","b"]}}"""),
            ),
        )

        assertEquals(listOf("x"), cfg.consensusModels)
    }

    @Test
    fun `analyzer tokens reach the rule config`() {
        val cfg = AiRuleConfig.fromPluginConfig(
            json("""{"analyzer":{"tokens":{"minResponse":3000,"maxAutoResponse":9000}}}"""),
        )

        assertEquals(3000, cfg.analyzerTokens.minResponse)
        assertEquals(9000, cfg.analyzerTokens.maxAutoResponse)
    }

    @Test
    fun `analyzer tokens floor and ceiling bound the auto-scaled response`() {
        val small = AiRuleAnalyzerUtils.resolveMaxResponseTokens(
            configuredMax = 100,
            defaultMax = 100,
            systemPrompt = "s",
            userPrompt = "u",
            tokens = AiAnalyzerTokensConfig(minResponse = 3000),
        )
        val huge = AiRuleAnalyzerUtils.resolveMaxResponseTokens(
            configuredMax = 100,
            defaultMax = 100,
            systemPrompt = "x".repeat(400_000),
            userPrompt = "u",
            tokens = AiAnalyzerTokensConfig(maxAutoResponse = 2000),
        )

        assertEquals(3000, small)
        assertEquals(2000, huge)
    }

    @Test
    fun `maxSystemPromptChars reaches the rule config`() {
        val cfg = AiRuleConfig.fromPluginConfig(json("""{"analyzer":{"tokens":{"maxSystemPromptChars":1234}}}"""))

        assertEquals(1234, cfg.analyzerTokens.maxSystemPromptChars)
        assertEquals(null, AiRuleConfig.fromPluginConfig(json("{}")).analyzerTokens.maxSystemPromptChars)
    }

    @Test
    fun `invalid analyzer tokens warn and fall back to the defaults`() {
        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) = Unit
        }
        listOf(
            """{"analyzer":{"tokens":{"charsPerTokenEstimate":0}}}""",
            """{"analyzer":{"tokens":{"minResponse":"many"}}}""",
            """{"analyzer":{"tokens":{"maxSystemPromptChars":-5}}}""",
        ).forEach { text ->
            val cfg = AiRuleConfig.fromPluginConfig(json(text), logger)

            assertEquals(AiAnalyzerTokensConfig(), cfg.analyzerTokens, text)
        }

        assertEquals(3, warnings.count { "ai.analyzer.tokens" in it }, warnings.toString())
    }

    @Test
    fun `invalid analyzer tokens never throw from the plugin config`() {
        val cfg = AiPluginConfig.fromPluginConfig(
            AiPluginConfig.topLevelAiBlockToPluginConfigElement(
                json("""{"analyzer":{"tokens":{"charsPerTokenEstimate":0}}}"""),
            ),
        )

        assertEquals(AiAnalyzerTokensConfig(), cfg.analyzer.tokens)
    }

    @Test
    fun `diff cap limits still reach the rule config`() {
        val cfg = AiRuleConfig.fromPluginConfig(json("""{"limits":{"maxDiffCharsTotal":5000,"maxDiffCharsPerFile":900}}"""))

        assertEquals(5000, cfg.maxDiffCharsTotal)
        assertEquals(900, cfg.maxDiffCharsPerFile)
    }
}
