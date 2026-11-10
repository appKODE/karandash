package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiProviderException
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Top-level `ai` keys read by [AiRuleModule] and [AiPipelineExtension] at run time. */
class AiRuleModuleKeyBehaviourTest {
    private val failing = object : AiAnalyzerModule {
        override val id: String = "risk-scoring"
        override val name: String = "risk-scoring"
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val analyzerId: String = "risk-scoring"
        override val featureKey: String = "riskScoring"
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 100
        override val needsScopeDetection: Boolean = false

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> =
            throw AiProviderException("provider down", retryable = true)
    }

    private val registry: ModuleRegistry = object : ModuleRegistry by buildTestRegistry() {
        override fun aiAnalyzers(): List<AiAnalyzerModule> = listOf(failing)

        override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = failing.takeIf { it.analyzerId == analyzerId }
    }

    private fun context(extra: Map<String, Any?>): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(
                mapOf("provider" to "custom", "baseUrl" to "http://localhost:1234/v1", "model" to "test-model") + extra,
            )

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private suspend fun runFailing(failOnError: Boolean): List<Finding> {
        val module = AiRuleModule(registry)
        module.initialize(context(mapOf("failOnError" to failOnError)))
        val findings = module.validate("risk-scoring", createTestMergeRequest(), JsonObject(emptyMap()))
        module.shutdown()
        return findings
    }

    @Test
    fun `ai failOnError true turns a provider failure into an error finding`() =
        runTest {
            val findings = runFailing(failOnError = true)
            assertEquals(1, findings.size, findings.toString())
            assertEquals(Severity.ERROR, findings.single().severity)
            assertTrue(findings.single().message.contains("AI analysis failed"))
        }

    @Test
    fun `ai failOnError false swallows a provider failure`() =
        runTest {
            assertTrue(runFailing(failOnError = false).isEmpty())
        }

    @Test
    fun `ai concurrency sets the number of run permits`() =
        runTest {
            val module = AiRuleModule(registry)
            module.initialize(context(mapOf("concurrency" to 3)))
            assertEquals(3, checkNotNull(module.runLimits()).second.availablePermits)
            module.shutdown()
        }

    @Test
    fun `ai contextLines sets the context lines requested from the platform`() {
        val config = buildJsonObject { put("contextLines", 7) }
        assertEquals(7, checkNotNull(AiPipelineExtension(buildTestRegistry()).fetchOptions(config)).contextLines)
        assertNull(AiPipelineExtension(buildTestRegistry()).fetchOptions(null))
    }

    @Test
    fun `ai costBudget costEstimate enabled false omits the cost summary`() =
        runTest {
            val on = AiRuleModule(registry)
            on.initialize(context(emptyMap()))
            assertTrue(on.consumeCostSummaryLines().isNotEmpty())
            on.shutdown()

            val off = AiRuleModule(registry)
            off.initialize(context(mapOf("costBudget" to mapOf("costEstimate" to mapOf("enabled" to false)))))
            assertTrue(off.consumeCostSummaryLines().isEmpty())
            off.shutdown()
        }
}
