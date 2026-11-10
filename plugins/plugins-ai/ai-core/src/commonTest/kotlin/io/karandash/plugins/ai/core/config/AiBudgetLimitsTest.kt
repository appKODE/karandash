package io.karandash.plugins.ai.core.config

import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.sdk.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiBudgetLimitsTest {
    private fun ruleConfig(json: String) = AiRuleConfig.fromPluginConfig(KarandashJson.Default.parseToJsonElement(json))

    private fun warningsFor(json: String): List<String> {
        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) = Unit
        }
        AiPluginConfig.topLevelAiBlockToPluginConfigElement(KarandashJson.Default.parseToJsonElement(json), logger)
        return warnings
    }

    @Test
    fun `without limits both diff caps derive from the budget minus the output reserve`() {
        val cfg = ruleConfig("""{"budget":{"maxInputTokens":1000,"reserveOutputTokens":200}}""")
        assertEquals(3200, cfg.maxDiffCharsTotal)
        assertEquals(3200, cfg.maxDiffCharsPerFile)
    }

    @Test
    fun `without any budget the diff caps are a ceiling above the largest auto budget`() {
        val cfg = ruleConfig("{}")
        assertEquals(256_000, cfg.maxDiffCharsTotal)
        assertEquals(256_000, cfg.maxDiffCharsPerFile)
        assertEquals(256_000, AiRuleConfig().maxDiffCharsTotal)
    }

    @Test
    fun `an explicit context budget above the auto maximum raises the diff ceiling`() {
        assertEquals(400_000, ruleConfig("""{"context":{"budget":"100000"}}""").maxDiffCharsTotal)
        assertEquals(256_000, ruleConfig("""{"context":{"budget":"auto"}}""").maxDiffCharsTotal)
    }

    @Test
    fun `context budget is read from the context block and auto reads as null`() {
        val parse = { json: String -> AiPluginConfig.fromPluginConfig(KarandashJson.Default.parseToJsonElement(json)).contextBudget }
        assertEquals(20_000, parse("""{"context":{"budget":20000}}"""))
        assertEquals(null, parse("""{"context":{"budget":"auto"}}"""))
        assertEquals(null, parse("{}"))
    }

    @Test
    fun `explicit limits diff caps still apply`() {
        val cfg = ruleConfig("""{"limits":{"maxDiffCharsTotal":900,"maxDiffCharsPerFile":300}}""")
        assertEquals(900, cfg.maxDiffCharsTotal)
        assertEquals(300, cfg.maxDiffCharsPerFile)
    }

    @Test
    fun `limits diff char caps are live limits and warn nothing`() {
        assertTrue(warningsFor("""{"limits":{"maxDiffCharsTotal":900,"maxDiffCharsPerFile":300}}""").isEmpty())
    }

    @Test
    fun `riskScoringMaxDiffChars is a live limit and warns nothing`() {
        assertTrue(warningsFor("""{"limits":{"riskScoringMaxDiffChars":3000}}""").isEmpty())
        assertEquals(3000, ruleConfig("""{"limits":{"riskScoringMaxDiffChars":3000}}""").riskScoring.maxDiffChars)
    }

    @Test
    fun `limits without diff char caps warn nothing`() {
        assertTrue(warningsFor("""{"limits":{"maxChunks":3}}""").isEmpty())
    }

    @Test
    fun `budget keys are optional and defaulted`() {
        val budget = KarandashJson.Default.decodeFromString(AiBudgetConfig.serializer(), "{}")
        assertEquals(null, budget.maxInputTokens)
        assertEquals(0, budget.reserveOutputTokens)
        assertEquals(emptyMap(), budget.weights)
        assertEquals(AiBudgetConfig.DEFAULT_MAX_INPUT_TOKENS, budget.promptCapTokens)
    }

    @Test
    fun `the output reserve comes off the prompt cap and never below zero`() {
        assertEquals(800, AiBudgetConfig(maxInputTokens = 1000, reserveOutputTokens = 200).promptCapTokens)
        assertEquals(0, AiBudgetConfig(maxInputTokens = 100, reserveOutputTokens = 200).promptCapTokens)
    }

    @Test
    fun `a weight overrides a context part priority by its kebab or camel name`() {
        val budget = AiBudgetConfig(weights = mapOf("crossFileContext" to 999, "relatedFileContext" to 5))
        assertEquals(999, budget.priorityOf("crossFileContext", 110))
        assertEquals(5, budget.priorityOf("related-file-context", 50))
        assertEquals(100, budget.priorityOf("dependencyContext", 100))
    }
}
