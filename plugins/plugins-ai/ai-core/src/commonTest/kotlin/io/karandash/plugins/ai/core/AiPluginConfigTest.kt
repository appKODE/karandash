package io.karandash.plugins.ai.core

import io.karandash.api.support.mapToJsonElement
import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.sdk.Logger
import io.karandash.sdk.content.ContentSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiPluginConfigTest {
    @Test
    fun `concurrency defaults to MAX_CONCURRENCY`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(emptyMap()))
        assertEquals(AiPluginConfig.MAX_CONCURRENCY, config.concurrency)
    }

    @Test
    fun `concurrency is clamped to range 1 to MAX_CONCURRENCY`() {
        val config0 = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("concurrency" to 0)))
        assertEquals(1, config0.concurrency)

        val config10 = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("concurrency" to 10)))
        assertEquals(10, config10.concurrency)

        val config30 = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("concurrency" to 30)))
        assertEquals(AiPluginConfig.MAX_CONCURRENCY, config30.concurrency)

        val config3 = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("concurrency" to 3)))
        assertEquals(3, config3.concurrency)
    }

    @Test
    fun `concurrency handles negative values`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("concurrency" to -1)))
        assertEquals(1, config.concurrency)
    }

    @Test
    fun `context map parsing from config`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("context" to mapOf("team_name" to "platform", "style" to "kotlin")))
        )
        assertEquals(mapOf("team_name" to "platform", "style" to "kotlin"), config.context)
    }

    @Test
    fun `context defaults to empty map`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(emptyMap()))
        assertTrue(config.context.isEmpty())
    }

    @Test
    fun `context coerces non-string values to string`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("context" to mapOf("count" to 42, "flag" to true)))
        )
        assertEquals("42", config.context["count"])
        assertEquals("true", config.context["flag"])
    }

    @Test
    fun `reviewMode defaults to CHANGED`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(emptyMap()))
        assertEquals(DiffMode.CHANGED, config.reviewMode)
    }

    @Test
    fun `reviewMode parses from string`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("reviewMode" to "patch")))
        assertEquals(DiffMode.PATCH, config.reviewMode)
    }

    @Test
    fun `dryRun defaults to false`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(emptyMap()))
        assertFalse(config.dryRunAi)
    }

    @Test
    fun `dryRun parses boolean true`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("dryRun" to true)))
        assertTrue(config.dryRunAi)
    }

    @Test
    fun `dryRun parses boolean false`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("dryRun" to false)))
        assertFalse(config.dryRunAi)
    }

    @Test
    fun `dryRun parses map with ai scope`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("dryRun" to mapOf("scope" to "ai")))
        )
        assertTrue(config.dryRunAi)
    }

    @Test
    fun `dryRun parses map with all scope`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("dryRun" to mapOf("scope" to "all")))
        )
        assertTrue(config.dryRunAi)
    }

    @Test
    fun `dryRun map with unknown scope defaults to false`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("dryRun" to mapOf("scope" to "reporters")))
        )
        assertFalse(config.dryRunAi)
    }

    @Test
    fun `systemPrompts parses from config`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("systemPrompts" to mapOf("risk-scoring" to mapOf("inline" to "custom"))))
        )
        assertTrue(config.systemPrompts.isNotEmpty())
    }

    @Test
    fun `extraInstructions parses inline string as ContentSource Inline`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("extraInstructions" to "Focus on security."))
        )
        val source = assertIs<ContentSource.Inline>(config.extraInstructions)
        assertEquals("Focus on security.", source.value)
    }

    @Test
    fun `extraInstructions parses file object as ContentSource LocalFile`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("extraInstructions" to mapOf("file" to ".karandash/extra-instructions.md")))
        )
        val source = assertIs<ContentSource.LocalFile>(config.extraInstructions)
        assertEquals(".karandash/extra-instructions.md", source.path)
    }

    @Test
    fun `extraInstructions is null when not configured`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(emptyMap<String, Any>()))
        assertNull(config.extraInstructions)
    }

    @Test
    fun `bestPractices parses inline string as ContentSource Inline`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("bestPractices" to "Use ViewModel."))
        )
        val source = assertIs<ContentSource.Inline>(config.bestPractices)
        assertEquals("Use ViewModel.", source.value)
    }

    @Test
    fun `bestPractices parses file object as ContentSource LocalFile`() {
        val config = AiPluginConfig.fromPluginConfig(
            mapToJsonElement(mapOf("bestPractices" to mapOf("file" to ".karandash/best-practices.md")))
        )
        val source = assertIs<ContentSource.LocalFile>(config.bestPractices)
        assertEquals(".karandash/best-practices.md", source.path)
    }

    // --- code-suggestions block synthesis ---

    @Test
    fun `code-suggestions inline mode synthesizes includeFixSuggestions true`() {
        val raw = mapToJsonElement(
            mapOf(
                "code-suggestions" to mapOf(
                    "enabled" to true,
                    "mode" to "inline",
                    "min-severity" to "error",
                ),
            ),
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertEquals(true, result["includeFixSuggestions"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("inline", result["codeSuggestionMode"]?.jsonPrimitive?.content)
        assertEquals("error", result["fixSuggestionMinSeverity"]?.jsonPrimitive?.content)
    }

    @Test
    fun `code-suggestions separate mode keeps review-comments without suggestions`() {
        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) = Unit
        }
        val raw = mapToJsonElement(
            mapOf(
                "code-suggestions" to mapOf(
                    "enabled" to true,
                    "mode" to "separate",
                ),
            ),
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw, logger) as JsonObject

        assertEquals(false, result["includeFixSuggestions"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("separate", result["codeSuggestionMode"]?.jsonPrimitive?.content)
        assertTrue(warnings.none { "separate" in it }, warnings.toString())
    }

    @Test
    fun `code-suggestions disabled sets includeFixSuggestions false`() {
        val raw = mapToJsonElement(
            mapOf(
                "code-suggestions" to mapOf(
                    "enabled" to false,
                    "mode" to "inline",
                ),
            ),
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertEquals(false, result["includeFixSuggestions"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `code-suggestions block without enabled implies enabled`() {
        val raw = mapToJsonElement(
            mapOf(
                "code-suggestions" to mapOf(
                    "mode" to "inline",
                ),
            ),
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertEquals(true, result["includeFixSuggestions"]?.jsonPrimitive?.booleanOrNull)
        // Feature should be implicitly enabled
        val features = result["features"] as? JsonObject
        assertNotNull(features, "features map should be present")
        assertTrue(
            features!!.containsKey("code-suggestions") || features.containsKey("codeSuggestions"),
            "code-suggestions feature should be implicitly enabled",
        )
    }

    @Test
    fun `code-suggestions defaults mode to inline`() {
        val raw = mapToJsonElement(
            mapOf(
                "code-suggestions" to mapOf(
                    "enabled" to true,
                ),
            ),
        )
        val result = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw) as JsonObject

        assertEquals("inline", result["codeSuggestionMode"]?.jsonPrimitive?.content)
        assertEquals(true, result["includeFixSuggestions"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `fixSuggestionMinSeverity propagates through topLevelAi to AiRuleConfig`() {
        val raw = mapToJsonElement(
            mapOf(
                "code-suggestions" to mapOf(
                    "enabled" to true,
                    "mode" to "inline",
                    "min-severity" to "error",
                ),
            ),
        )
        val processed = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw)
        val ruleConfig = AiRuleConfig.fromPluginConfig(processed)

        assertEquals(
            "error",
            ruleConfig.fixSuggestionMinSeverity,
            "fixSuggestionMinSeverity should be 'error' after full config propagation chain",
        )
        assertTrue(
            ruleConfig.includeFixSuggestions,
            "includeFixSuggestions should be true for inline mode",
        )
        assertEquals("inline", ruleConfig.codeSuggestionMode)
    }

    @Test
    fun `fixSuggestionMinSeverity defaults to warning when not set`() {
        val raw = mapToJsonElement(
            mapOf(
                "code-suggestions" to mapOf(
                    "enabled" to true,
                    "mode" to "inline",
                ),
            ),
        )
        val processed = AiPluginConfig.topLevelAiBlockToPluginConfigElement(raw)
        val ruleConfig = AiRuleConfig.fromPluginConfig(processed)

        assertEquals(
            "warning",
            ruleConfig.fixSuggestionMinSeverity,
            "fixSuggestionMinSeverity should default to 'warning' when not configured",
        )
    }

    @Test
    fun `fixSuggestionMinSeverity from empty config falls back to default`() {
        val ruleConfig = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(emptyMap())
        )

        assertEquals("warning", ruleConfig.fixSuggestionMinSeverity)
        assertEquals(true, ruleConfig.includeFixSuggestions)
        assertEquals("inline", ruleConfig.codeSuggestionMode)
    }

    // --- executionTimeout parsing ---

    @Test
    fun `executionTimeout parses duration string from config`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("executionTimeout" to "5m")))
        assertEquals(KarandashDuration.ofMinutes(5), config.executionTimeout)
    }

    @Test
    fun `executionTimeout defaults to null when absent`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(emptyMap()))
        assertNull(config.executionTimeout)
    }

    @Test
    fun `executionTimeout zero is preserved without clamping`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("executionTimeout" to "0s")))
        assertEquals(KarandashDuration.ZERO, config.executionTimeout)
    }

    @Test
    fun `contextLines defaults to FetchOptions DEFAULT_CONTEXT_LINES`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(emptyMap()))
        assertEquals(io.karandash.api.platform.FetchOptions.DEFAULT_CONTEXT_LINES, config.contextLines)
    }

    @Test
    fun `contextLines parses from config`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("context-lines" to 10)))
        assertEquals(10, config.contextLines)
    }

    @Test
    fun `contextLines clamped to valid range`() {
        val tooLow = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("context-lines" to -5)))
        assertEquals(io.karandash.api.platform.FetchOptions.MIN_CONTEXT_LINES, tooLow.contextLines)

        val tooHigh = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("context-lines" to 999)))
        assertEquals(io.karandash.api.platform.FetchOptions.MAX_CONTEXT_LINES, tooHigh.contextLines)
    }

    @Test
    fun `contextLines zero is valid`() {
        val config = AiPluginConfig.fromPluginConfig(mapToJsonElement(mapOf("context-lines" to 0)))
        assertEquals(0, config.contextLines)
    }
}
