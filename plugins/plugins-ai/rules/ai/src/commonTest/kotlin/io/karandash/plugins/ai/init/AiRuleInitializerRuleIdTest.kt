package io.karandash.plugins.ai.init

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.plugins.ai.buildTestRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Per-rule map keys (`models`, `ruleProviders`) are looked up by exact rule id, so every spelling
 * of a rule id must resolve to it -- the camelCase-to-kebab conversion alone missed snake_case keys.
 */
class AiRuleInitializerRuleIdTest {
    private fun context(pluginConfig: Map<String, Any?>): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(pluginConfig)

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    @Test
    fun `per-rule map keys in any spelling resolve to the rule id`() =
        runTest {
            val ctx = context(
                mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "models" to mapOf("risk_scoring" to "snake-model", "descriptionQuality" to "camel-model"),
                    "ruleProviders" to mapOf("Review_Comments" to "ollama"),
                ),
            )

            val result = assertNotNull(AiRuleInitializer(buildTestRegistry(), null).initialize(ctx))

            assertEquals("snake-model", result.perRuleModels["risk-scoring"])
            assertEquals("camel-model", result.perRuleModels["description-quality"])
            assertEquals("ollama", result.normalizedRuleProviders["review-comments"])
        }
}
