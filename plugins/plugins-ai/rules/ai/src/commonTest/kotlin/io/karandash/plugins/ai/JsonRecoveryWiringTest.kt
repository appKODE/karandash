package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.finding.Finding
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ai.filter.attemptJsonRecovery: false` reaches the AI service an analyzer calls through `AiRuleModule`. */
class JsonRecoveryWiringTest {
    private val analyzer = ProbeAnalyzer()
    private val provider = TruncatingProvider()

    @Test
    fun `attemptJsonRecovery false discards a truncated response before the analyzer sees it`() =
        runTest {
            val module = initialized(mapOf("filter" to mapOf("attemptJsonRecovery" to false)))

            module.validate(analyzer.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))

            assertEquals("", analyzer.seen)
            module.shutdown()
        }

    @Test
    fun `attemptJsonRecovery left on hands the truncated response to the analyzer`() =
        runTest {
            val module = initialized(emptyMap())

            module.validate(analyzer.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))

            assertEquals(TRUNCATED, analyzer.seen)
            module.shutdown()
        }

    private suspend fun initialized(extra: Map<String, Any>): AiRuleModule {
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun aiAnalyzers(): List<AiAnalyzerModule> = listOf(analyzer)

            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { it.analyzerId == analyzerId }

            override fun aiProviders(): List<AiProviderModule> = listOf(provider)

            override fun findAiProvider(providerId: String): AiProviderModule? = provider.takeIf { providerId == FAKE }
        }
        val module = AiRuleModule(registry)
        val config = mapOf("provider" to FAKE, "model" to "base-m", "maxTokenBudget" to BUDGET) + extra
        assertTrue(module.initialize(context(config)))
        return module
    }

    private fun context(config: Map<String, Any>) =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)

            override fun env(name: String): String? = null

            override fun log(message: String) = Unit

            override fun warn(message: String) = Unit

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    /** Makes one service call and keeps the text it got back. */
    private class ProbeAnalyzer : AiAnalyzerModule {
        var seen: String? = null
        override val analyzerId: String = "probe"
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 100

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            val request = AiCompletionRequest(prompt = "review", systemPrompt = "s", model = context.model, maxTokens = 100)
            seen = context.aiService.complete(request, null).content
            return emptyList()
        }
    }

    private class TruncatingProvider : AiProviderModule {
        override val providerId: String = FAKE
        override val id: String = "io.karandash.ai.provider.$FAKE"
        override val name: String = FAKE
        override val version: String = "test"
        override val description: String = FAKE
        override val defaultModel: String = "base-m"
        override val apiKeyEnvVar: String? = null
        override val requiresApiKey: Boolean = false

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse =
            AiCompletionResponse(TRUNCATED, 1, 1, 2, request.model)
    }

    private companion object {
        const val FAKE = "fake"
        const val BUDGET = 1_000_000
        const val TRUNCATED = """{"findings":[{"message":"a"},{"message":"b"""
    }
}
