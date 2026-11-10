package io.karandash.plugins.ai.core

import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.PartialReviewStrategy
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Normalized (camelCase) `ai:` keys reach the typed AI config; dropped keys warn. */
class AiConfigCamelCaseKeysTest {
    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val aiBlock = buildJsonObject {
        put("provider", "openai")
        put("contextLines", 7)
        putJsonObject("contextQuality") {
            put("maxFilesToAnalyze", 4)
            putJsonObject("fullFileContext") { put("maxFiles", 2) }
        }
        putJsonObject("conventionContext") { put("enabled", false) }
        putJsonObject("sizePolicy") {
            putJsonObject("partialReview") {
                put("maxFiles", 3)
                put("strategy", "most-changed")
                putJsonArray("prioritizeTypes") { add(JsonPrimitive("kt")) }
            }
        }
        putJsonObject("reviewComments") {
            put("enabled", true)
            put("prompt", "ignored here")
        }
    }

    @Test
    fun `camelCase contextLines decodes`() {
        val pluginCfg = AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(aiBlock))
        assertEquals(7, pluginCfg.contextLines)
        assertEquals(7, AiPluginConfig.fromPluginConfig(aiBlock).contextLines)
    }

    @Test
    fun `contextQuality and conventionContext pass through to the plugin config`() {
        val element = AiPluginConfig.topLevelAiBlockToPluginConfigElement(aiBlock) as JsonObject
        assertNotNull(element["contextQuality"])
        assertNotNull(element["conventionContext"])
    }

    @Test
    fun `camelCase contextQuality decodes into the rule config`() {
        val element = AiPluginConfig.topLevelAiBlockToPluginConfigElement(aiBlock)
        val quality = AiRuleConfig.fromPluginConfig(element).contextQuality
        assertEquals(4, quality.maxFilesToAnalyze)
        assertEquals(2, quality.fullFileContext.maxFiles)
    }

    @Test
    fun `camelCase sizePolicy partialReview decodes`() {
        val element = AiPluginConfig.topLevelAiBlockToPluginConfigElement(aiBlock)
        val policy = assertNotNull(AiRuleConfig.fromPluginConfig(element).partialReviewPolicy)
        assertEquals(3, policy.maxFiles)
        assertEquals(PartialReviewStrategy.fromString("most-changed"), policy.strategy)
        assertEquals(listOf("kt"), policy.prioritizeTypes)
    }

    @Test
    fun `unsupported per-analyzer keys are listed in one warning`() {
        val logger = WarnLogger()
        AiPluginConfig.topLevelAiBlockToPluginConfigElement(aiBlock, logger)
        val warning = logger.warnings.single()
        assertTrue("reviewComments.prompt" in warning, warning)
        assertTrue("contextQuality" !in warning, warning)
    }
}
