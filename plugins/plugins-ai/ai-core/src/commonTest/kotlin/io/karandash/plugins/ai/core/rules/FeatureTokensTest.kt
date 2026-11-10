package io.karandash.plugins.ai.core.rules

import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTokensTest {
    private class Warnings : Logger {
        val messages = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            messages += message
        }

        override fun error(message: String) = Unit
    }

    private fun config(
        vararg limits: Pair<String, Int>,
        logger: Logger? = null,
    ) = AiRuleConfig.fromPluginConfig(
        JsonObject(mapOf("limits" to JsonObject(limits.associate { (k, v) -> k to JsonPrimitive(v) }))),
        logger,
    )

    @Test
    fun `an analyzer without a key of its own borrows the review group and warns once per borrowed key`() {
        val warnings = Warnings()
        val tokens = config("reviewMaxTokens" to 900, logger = warnings).tokensFor("prSplit")

        assertEquals(900, tokens.maxTokens)
        assertEquals(ReviewLimits.DEFAULT_MAX_TOKENS, tokens.defaultMaxTokens)
        assertEquals(1, warnings.messages.size, warnings.messages.toString())
        assertEquals(true, "ai.limits.prSplitMaxTokens" in warnings.messages.single())
    }

    @Test
    fun `its own key wins over the borrowed group and silences the warning`() {
        val warnings = Warnings()
        val config = config("descriptionMaxTokens" to 900, "reviewThreadAnalysisMaxTokens" to 333, logger = warnings)

        assertEquals(333, config.tokensFor("reviewThreadAnalysis").maxTokens)
        assertEquals(emptyList(), warnings.messages)
    }

    @Test
    fun `analyzers with their own group read it and an unknown key gets the review group`() {
        val config = config("codeQualityMaxTokens" to 777, "reviewMaxTokens" to 555)

        assertEquals(777, config.tokensFor("codeQuality").maxTokens)
        assertEquals(555, config.tokensFor("someoneElse").maxTokens)
    }
}
