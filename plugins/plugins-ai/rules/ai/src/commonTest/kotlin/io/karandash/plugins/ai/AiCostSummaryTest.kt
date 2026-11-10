package io.karandash.plugins.ai

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.config.keys.ModuleIds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the AI cost summary reaches the CLI through [AiPipelineExtension.costSummary] exactly once,
 * and that [AiRuleModule.shutdown] still logs it when nothing consumed it first.
 */
class AiCostSummaryTest {
    private val logs = mutableListOf<String>()

    private val context: PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(
                mapOf("provider" to "custom", "baseUrl" to "http://localhost:1234/v1", "model" to "test-model"),
            )

            override fun env(name: String): String? = null

            override fun log(message: String) {
                logs += message
            }

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private fun registryServing(module: AiRuleModule): ModuleRegistry =
        object : ModuleRegistry by buildTestRegistry() {
            override fun get(id: String): Module? = if (id == ModuleIds.AI_ANALYSIS) module else null

            override fun ruleModules(): List<RuleModule> = listOf(module)
        }

    @Test
    fun `costSummary returns the summary once`() =
        runTest {
            val module = AiRuleModule(buildTestRegistry())
            assertTrue(module.initialize(context))
            val extension = AiPipelineExtension(registryServing(module))

            val summary = extension.costSummary()

            assertNotNull(summary)
            assertTrue(summary.startsWith("AI plugin cost estimate"), summary)
            assertNull(extension.costSummary())
            module.shutdown()
            assertTrue(logs.none { it.startsWith("AI plugin cost estimate") }, "consumed summary must not be logged again")
        }

    @Test
    fun `shutdown logs the summary when nothing consumed it`() =
        runTest {
            val module = AiRuleModule(buildTestRegistry())
            assertTrue(module.initialize(context))

            module.shutdown()

            assertEquals(1, logs.count { it.startsWith("AI plugin cost estimate") }, "logs: $logs")
        }
}
