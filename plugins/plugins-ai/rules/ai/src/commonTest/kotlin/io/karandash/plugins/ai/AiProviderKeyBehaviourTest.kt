package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.context.ValidationContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.createTestWarning
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Behaviour of the provider and model selection keys of the `ai` block, seen at the provider request. */
class AiProviderKeyBehaviourTest {
    private val main = RecordingProvider(MAIN)
    private val alt = RecordingProvider(ALT)
    private val first = ProbeAnalyzer("first")
    private val second = ProbeAnalyzer("second")

    @Test
    fun `ai model reaches the provider request of a rule without an override`() =
        runTest {
            val module = initialized(emptyMap())

            module.validate(first.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))

            assertEquals(BASE_MODEL, main.requests.single().model)
            module.shutdown()
        }

    @Test
    fun `ai models entry overrides the model for that rule only`() =
        runTest {
            val module = initialized(mapOf("models" to mapOf("first" to RULE_MODEL)))

            module.validate(first.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))
            module.validate(second.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))

            assertEquals(listOf(RULE_MODEL, BASE_MODEL), main.requests.map { it.model })
            module.shutdown()
        }

    @Test
    fun `ai providers with ruleProviders sends that rule to the named provider`() =
        runTest {
            val module = initialized(mapOf("providers" to mapOf("fast" to ALT), "ruleProviders" to mapOf("first" to "fast")))

            module.validate(first.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))
            module.validate(second.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))

            assertEquals(listOf(1, 1), listOf(alt.requests.size, main.requests.size))
            module.shutdown()
        }

    @Test
    fun `ai provider selects the provider that receives the request`() =
        runTest {
            val module = initialized(mapOf("provider" to ALT))

            module.validate(first.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))

            assertTrue(main.requests.isEmpty())
            assertEquals(1, alt.requests.size)
            module.shutdown()
        }

    @Test
    fun `ai routing enabled picks the strongest tier model when the risk is unknown`() =
        runTest {
            val routing = mapOf(
                "tiers" to listOf(
                    mapOf("name" to "strong", "models" to listOf(STRONG_MODEL)),
                    mapOf("name" to "cheap", "models" to listOf("small-m")),
                ),
            )
            val routed = initialized(mapOf("routing" to routing + ("enabled" to true)))
            routed.validate(first.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))
            routed.shutdown()
            val off = initialized(mapOf("routing" to routing + ("enabled" to false)))
            off.validate(second.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))
            off.shutdown()

            assertEquals(listOf(STRONG_MODEL, BASE_MODEL), main.requests.map { it.model })
        }

    @Test
    fun `ai rules fallback strategy skips the rule when a covered rule found enough`() =
        runTest {
            val rules = mapOf("first" to mapOf("strategy" to "fallback", "covers" to listOf("title-format"), "minSeverity" to "error"))
            val module = initialized(mapOf("rules" to rules))
            val mr = createTestMergeRequest()

            fun prior(severity: Severity) = ValidationContext(listOf(Finding(severity = severity, message = "m", rule = "title-format")))
            module.validateWithFindings(first.analyzerId, mr, JsonObject(emptyMap()), prior(Severity.WARNING))
            assertEquals(1, main.requests.size)
            module.validateWithFindings(first.analyzerId, mr, JsonObject(emptyMap()), prior(Severity.ERROR))
            assertEquals(1, main.requests.size)
            module.shutdown()
        }

    private suspend fun initialized(extra: Map<String, Any>): AiRuleModule {
        val analyzers = listOf(first, second)
        val providers = listOf(main, alt)
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun aiAnalyzers(): List<AiAnalyzerModule> = analyzers

            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzers.find { it.analyzerId == analyzerId }

            override fun aiProviders(): List<AiProviderModule> = providers

            override fun findAiProvider(providerId: String): AiProviderModule? = providers.find { it.providerId == providerId }
        }
        val module = AiRuleModule(registry)
        val config = mapOf("provider" to MAIN, "model" to BASE_MODEL, "maxTokenBudget" to BUDGET) + extra
        assertTrue(module.initialize(context(config)))
        return module
    }

    private fun context(config: Map<String, Any>) =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)
            override val logger = NoOpLogger

            override fun env(name: String): String? = null

            override fun log(message: String) = Unit

            override fun warn(message: String) = Unit

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private class ProbeAnalyzer(
        override val analyzerId: String,
    ) : AiAnalyzerModule {
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 100

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            context.aiService.complete(AiCompletionRequest(prompt = "review", model = context.model), null)
            return listOf(createTestWarning(ruleId = analyzerId, message = "$analyzerId finding"))
        }
    }

    private class RecordingProvider(
        override val providerId: String,
    ) : AiProviderModule,
        AiProvider {
        val requests = mutableListOf<AiCompletionRequest>()
        override val id: String = "io.karandash.ai.provider.$providerId"
        override val name: String = providerId
        override val version: String = "test"
        override val description: String = providerId
        override val defaultModel: String = "base-m"
        override val apiKeyEnvVar: String? = null
        override val requiresApiKey: Boolean = false

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
            requests += request
            return AiCompletionResponse("""{"findings": []}""", 1, 1, 2, request.model)
        }

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse = AiResponse("""{"findings": []}""", 1, 1, 2, model)

        override suspend fun close() = Unit
    }

    private companion object {
        const val MAIN = "fake"
        const val ALT = "fake-alt"
        const val BASE_MODEL = "gpt-4o-mini"
        const val RULE_MODEL = "rule-m"
        const val STRONG_MODEL = "big-m"
        const val BUDGET = 1_000_000
    }
}
