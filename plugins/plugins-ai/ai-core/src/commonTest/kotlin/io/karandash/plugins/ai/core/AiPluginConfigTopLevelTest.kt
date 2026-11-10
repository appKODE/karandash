package io.karandash.plugins.ai.core

import io.karandash.api.support.mapToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [AiPluginConfig.topLevelAiBlockToPluginConfigElement] conversion.
 */
class AiPluginConfigTopLevelTest {
    @Test
    fun `top-level keys pass through unchanged`() {
        val raw = mapToJsonElement(
            mapOf(
                "provider" to "litellm",
                "model" to "kimi-2.5",
                "maxTokenBudget" to 30000,
                "failOnError" to false,
            )
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertEquals("litellm", result["provider"]?.jsonPrimitive?.contentOrNull)
        assertEquals("kimi-2.5", result["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(30000, result["maxTokenBudget"]?.jsonPrimitive?.int)
        assertEquals(false, result["failOnError"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `sub-rule maps decomposed into features models temperatures`() {
        val raw = mapToJsonElement(
            mapOf(
                "provider" to "openai",
                "riskScoring" to mapOf("enabled" to true, "model" to "gpt-4", "temperature" to 0.3),
                "reviewComments" to mapOf("enabled" to false),
                "prSplit" to mapOf("temperature" to 0.7),
            )
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        val features = result["features"]?.jsonObject ?: error("features missing")
        assertEquals(true, features["riskScoring"]?.jsonPrimitive?.boolean)
        assertEquals(false, features["reviewComments"]?.jsonPrimitive?.boolean)
        assertFalse("prSplit" in features, "enabled not set → not in features map")

        val models = result["models"]?.jsonObject ?: error("models missing")
        assertEquals("gpt-4", models["riskScoring"]?.jsonPrimitive?.contentOrNull)
        assertFalse("reviewComments" in models, "no model override for reviewComments")

        val temperatures = result["temperatures"]?.jsonObject ?: error("temperatures missing")
        assertEquals(0.3, temperatures["riskScoring"]?.jsonPrimitive?.double)
        assertEquals(0.7, temperatures["prSplit"]?.jsonPrimitive?.double)
    }

    @Test
    fun `existing features map merged with sub-rule features`() {
        val raw = mapToJsonElement(
            mapOf(
                "features" to mapOf("existingFeature" to true),
                "riskScoring" to mapOf("enabled" to true),
            )
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        val features = result["features"]?.jsonObject ?: error("features missing")
        assertEquals(true, features["existingFeature"]?.jsonPrimitive?.boolean)
        assertEquals(true, features["riskScoring"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `empty ai block produces minimal config`() {
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(mapToJsonElement(emptyMap())) as JsonObject

        val features = result["features"]?.jsonObject ?: error("features missing")
        assertTrue(features.isEmpty())
    }

    @Test
    fun `unknown scalar keys pass through`() {
        val raw = mapToJsonElement(
            mapOf(
                "provider" to "openai",
                "someNewField" to "value",
            )
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertEquals("value", result["someNewField"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `topLevelAiBlockToPluginConfigElement preserves sizePolicy key`() {
        val raw = mapToJsonElement(
            mapOf(
                "provider" to "openai",
                "sizePolicy" to mapOf("exclude" to mapOf("patterns" to listOf("**/*.yml"))),
            )
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertNotNull(result["sizePolicy"], "sizePolicy key should be preserved in output")
    }

    @Test
    fun `topLevelAiBlockToPluginConfigElement preserves size-policy key`() {
        val raw = mapToJsonElement(
            mapOf(
                "provider" to "openai",
                "size-policy" to mapOf("exclude" to mapOf("patterns" to listOf("**/*.yml"))),
            )
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertNotNull(result["size-policy"], "size-policy key should be preserved in output")
    }

    @Test
    fun `mode key treated as top-level without warning`() {
        val raw = mapToJsonElement(
            mapOf(
                "provider" to "openai",
                "mode" to "supplement",
            )
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertEquals("supplement", result["mode"]?.jsonPrimitive?.contentOrNull)
        // mode must NOT appear in features (it's top-level, not a sub-rule)
        val features = result["features"]?.jsonObject ?: error("features missing")
        assertFalse("mode" in features, "mode should not be decomposed into features")
    }

    @Test
    fun `priority key treated as top-level`() {
        val raw = mapToJsonElement(
            mapOf(
                "provider" to "openai",
                "priority" to 200,
            )
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertEquals(200, result["priority"]?.jsonPrimitive?.int)
        val features = result["features"]?.jsonObject ?: error("features missing")
        assertFalse("priority" in features, "priority should not be decomposed into features")
    }

    @Test
    fun `concernArea key treated as top-level`() {
        val raw = mapToJsonElement(
            mapOf(
                "provider" to "openai",
                "concernArea" to "code-quality",
            )
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertEquals("code-quality", result["concernArea"]?.jsonPrimitive?.contentOrNull)
        val features = result["features"]?.jsonObject ?: error("features missing")
        assertFalse("concernArea" in features, "concernArea should not be decomposed into features")
    }

    @Test
    fun `string number deserializes to int via lenient json`() {
        // Simulates env interpolation: ${MAX_TOKENS} → "30000" (string primitive)
        val raw = JsonObject(
            mapOf(
                "provider" to JsonPrimitive("openai"),
                "maxTokenBudget" to JsonPrimitive("30000"),
            )
        )
        val pluginConfig = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw)
        val config = AiPluginConfig.fromPluginConfig(pluginConfig)

        assertEquals(30000, config.maxTokenBudget)
    }

    @Test
    fun `round-trip through fromPluginConfig succeeds`() {
        val raw = mapToJsonElement(
            mapOf(
                "provider" to "litellm",
                "model" to "kimi-2.5",
                "maxTokenBudget" to 30000,
                "riskScoring" to mapOf("enabled" to true, "model" to "gpt-4"),
                "reviewComments" to mapOf("enabled" to true, "temperature" to 0.5),
            )
        )
        val pluginConfig = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw)
        val config = AiPluginConfig.fromPluginConfig(pluginConfig)

        assertEquals("litellm", config.provider)
        assertEquals("kimi-2.5", config.model)
        assertEquals(30000, config.maxTokenBudget)
        assertEquals(true, config.features["riskScoring"]?.jsonPrimitive?.boolean)
        assertEquals(true, config.features["reviewComments"]?.jsonPrimitive?.boolean)
        assertEquals("gpt-4", config.models["riskScoring"])
        assertEquals(0.5, config.temperatures["reviewComments"])
    }

    @Test
    fun `per-analyzer reasoning is carried into reasonings`() {
        val raw = mapToJsonElement(
            mapOf(
                "reasoning" to "direct",
                "reviewComments" to mapOf("reasoning" to "semi-formal"),
            ),
        )
        val config = AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw))

        assertEquals("direct", config.reasoning)
        assertEquals(mapOf("reviewComments" to "semi-formal"), config.reasonings)
    }

    @Test
    fun `feedback object form sets feedbackEnabled and skipRepeatFindingTypes`() {
        val raw = mapToJsonElement(
            mapOf("feedback" to mapOf("enabled" to true, "skipRepeatFindingTypes" to true)),
        )
        val config = AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw))

        assertTrue(config.feedbackEnabled)
        assertTrue(config.feedbackSkipRepeatFindingTypes)
    }

    @Test
    fun `feedback reply judge defaults to off and reads llm`() {
        fun judge(feedback: Map<String, Any?>) =
            AiPluginConfig
                .fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(mapToJsonElement(mapOf("feedback" to feedback))))
                .feedbackReplyJudge

        assertEquals(ReplyJudgeMode.OFF, judge(mapOf("enabled" to true)))
        assertEquals(ReplyJudgeMode.OFF, judge(mapOf("replyJudge" to "off")))
        assertEquals(ReplyJudgeMode.LLM, judge(mapOf("replyJudge" to "llm")))
    }

    @Test
    fun `feedback reply judge parses any case and falls back to off with a warning`() {
        val warnings = mutableListOf<String>()
        val logger = object : io.karandash.logger.Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) = Unit
        }

        fun judge(value: String) =
            AiPluginConfig
                .fromPluginConfig(
                    AiPluginConfig.topLevelAiBlockToPluginConfigElement(
                        mapToJsonElement(mapOf("feedback" to mapOf("replyJudge" to value)))
                    ),
                    logger,
                ).feedbackReplyJudge

        assertEquals(ReplyJudgeMode.LLM, judge("LLM"))
        assertEquals(ReplyJudgeMode.OFF, judge("Off"))
        assertTrue(warnings.isEmpty(), "valid values must not warn: $warnings")
        assertEquals(ReplyJudgeMode.OFF, judge("on"))
        assertEquals(ReplyJudgeMode.OFF, judge("lmm"))
        assertEquals(2, warnings.count { "replyJudge" in it })
    }

    @Test
    fun `flat feedbackReplyJudge wins over feedback replyJudge`() {
        val raw = mapToJsonElement(mapOf("feedbackReplyJudge" to "off", "feedback" to mapOf("replyJudge" to "llm")))

        assertEquals(ReplyJudgeMode.OFF, AiPluginConfig.fromPluginConfig(raw).feedbackReplyJudge)
    }

    @Test
    fun `explicit feedbackEnabled wins over feedback enabled`() {
        val raw = mapToJsonElement(mapOf("feedbackEnabled" to false, "feedback" to mapOf("enabled" to true)))

        assertFalse(AiPluginConfig.fromPluginConfig(raw).feedbackEnabled)
    }

    @Test
    fun `review comments max comments is promoted unless the top-level key is set`() {
        val nested = mapToJsonElement(mapOf("reviewComments" to mapOf("maxComments" to 5)))
        val explicit = mapToJsonElement(mapOf("maxComments" to 8, "reviewComments" to mapOf("maxComments" to 5)))

        val promoted = AiPluginConfig.topLevelAiBlockToPluginConfigElement(nested) as JsonObject
        val kept = AiPluginConfig.topLevelAiBlockToPluginConfigElement(explicit) as JsonObject

        assertEquals(5, promoted["maxComments"]?.jsonPrimitive?.int)
        assertEquals(8, kept["maxComments"]?.jsonPrimitive?.int)
    }

    @Test
    fun `per-analyzer priority is carried into priorities and a non-integer one is dropped`() {
        val raw = mapToJsonElement(
            mapOf(
                "codeSuggestions" to mapOf("priority" to 5),
                "riskScoring" to mapOf("priority" to "high"),
            )
        )
        val config = AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw))

        assertEquals(mapOf("codeSuggestions" to 5), config.priorities)
    }
}
