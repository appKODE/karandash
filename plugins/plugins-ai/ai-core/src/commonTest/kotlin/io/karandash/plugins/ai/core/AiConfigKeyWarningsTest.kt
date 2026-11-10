package io.karandash.plugins.ai.core

import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Live `ai:` keys do not warn. */
class AiConfigKeyWarningsTest {
    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private fun warningsFor(ai: Map<String, Any?>): List<String> {
        val log = WarnLogger()
        AiPluginConfig.topLevelAiBlockToPluginConfigElement(mapToJsonElement(ai), log)
        return log.warnings
    }

    @Test
    fun `per-analyzer priority and concernArea are not reported as unsupported`() {
        val warnings = warningsFor(
            mapOf("reviewComments" to mapOf("enabled" to true, "priority" to 10, "concernArea" to "security")),
        )

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `live enrichment keys are not reported as unsupported`() {
        val warnings = warningsFor(
            mapOf("enrichment" to mapOf("generateDescription" to true, "generateTitle" to true, "skipIfDescriptionExists" to false)),
        )

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `formerly dead ai keys are live and warn nothing`() {
        val warnings = warningsFor(
            mapOf(
                "consensus" to mapOf("models" to listOf("a", "b"), "strategy" to "majority"),
                "analyzer" to mapOf("tokens" to mapOf("minResponse" to 1000)),
                "costBudget" to mapOf("maxCostPerRunUsd" to 1.5),
                "enrichment" to mapOf("model" to "m", "maxTokens" to 500),
                "hooks" to mapOf("post-validation" to mapOf("prompt" to "p", "action" to "METADATA")),
            ),
        )

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `live hook actions do not warn`() {
        val hooks = listOf("log", "comment", "finding", "metadata").associateWith { mapOf("prompt" to "p", "action" to it) }

        assertEquals(emptyList(), warningsFor(mapOf("hooks" to hooks)))
    }

    @Test
    fun `live keys in the same blocks do not warn`() {
        val warnings = warningsFor(
            mapOf(
                "consensusModels" to listOf("a", "b"),
                "costBudget" to mapOf("maxTokensPerRun" to 1000),
                "enrichment" to mapOf("enabled" to true),
            ),
        )

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `diff cap limits and maxSystemPromptChars warn nothing`() {
        val warnings = warningsFor(
            mapOf(
                "limits" to mapOf("maxDiffCharsTotal" to 5000, "maxDiffCharsPerFile" to 900, "riskScoringMaxDiffChars" to 700),
                "analyzer" to mapOf("tokens" to mapOf("maxSystemPromptChars" to 4000)),
            ),
        )

        assertEquals(emptyList(), warnings)
    }
}
