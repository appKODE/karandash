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
import io.karandash.logger.Logger
import io.karandash.models.finding.Finding
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.createTestWarning
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The enrichment `model` / `maxTokens` override, `analyzer.tokens.maxSystemPromptChars` and
 * `costBudget.maxCostPerRunUsd` reach the provider and the skip evaluator through `AiRuleModule`; so do
 * `ai.debug.prompt` and `ai.debug.promptDir`, which write the sent request to files.
 */
class RuleCallOverrideWiringTest {
    private val provider = RecordingProvider()
    private val first = ProbeAnalyzer("first")
    private val second = ProbeAnalyzer("second")

    @Test
    fun `the wrapped service receives the overridden model tokens and system prompt cap`() =
        runTest {
            val module = initialized(mapOf("analyzer" to mapOf("tokens" to mapOf("maxSystemPromptChars" to PROMPT_CAP))))
            val override = JsonObject(mapOf("model" to JsonPrimitive(PRICED_MODEL), "maxTokens" to JsonPrimitive(OVERRIDE_TOKENS)))

            module.validate(first.analyzerId, createTestMergeRequest(), override)

            val sent = provider.requests.single()
            assertEquals(PRICED_MODEL, sent.model)
            assertEquals(OVERRIDE_TOKENS, sent.maxTokens)
            assertEquals(PROMPT_CAP, sent.systemPrompt?.length)
            module.shutdown()
        }

    @Test
    fun `maxCostPerRunUsd makes the evaluator skip the next rule`() =
        runTest {
            val module = initialized(
                mapOf(
                    "costBudget" to mapOf("maxCostPerRunUsd" to CAP_USD, "maxTokensPerRun" to BUDGET),
                    // A 1-token reply reserve: the default 2000 alone is over the 400 tokens the USD cap buys.
                    "budget" to mapOf("reserveOutputTokens" to 1),
                ),
            )
            val override = JsonObject(mapOf("model" to JsonPrimitive(PRICED_MODEL)))

            module.validate(first.analyzerId, createTestMergeRequest(), override)
            module.validate(second.analyzerId, createTestMergeRequest(), override)

            assertEquals(1, first.calls)
            assertEquals(0, second.calls, "the second rule ran past max-cost-per-run-usd")
            module.shutdown()
        }

    @Test
    fun `without a cost cap the second rule runs`() =
        runTest {
            val module = initialized(emptyMap())
            val override = JsonObject(mapOf("model" to JsonPrimitive(PRICED_MODEL)))

            module.validate(first.analyzerId, createTestMergeRequest(), override)
            module.validate(second.analyzerId, createTestMergeRequest(), override)

            assertEquals(1, second.calls)
            module.shutdown()
        }

    @Test
    fun `debug prompt artifact writes the sent request of the rule under promptDir`() =
        runTest {
            val dir = Path(TestFiles.createTestTempDirectory("karandash-debug-wiring"), "dump")
            val module = initialized(mapOf("debug" to mapOf("prompt" to "artifact", "promptDir" to dir.toString())))

            module.validate(first.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))

            val names = SystemFileSystem.list(dir).map { it.name }
            assertTrue("${first.analyzerId}-1.prompt.txt" in names, names.toString())
            assertTrue("${first.analyzerId}-1.system.txt" in names, names.toString())
            module.shutdown()
        }

    @Test
    fun `debug prompt off writes nothing to promptDir`() =
        runTest {
            val dir = Path(TestFiles.createTestTempDirectory("karandash-debug-wiring"), "dump")
            val module = initialized(mapOf("debug" to mapOf("prompt" to "off", "promptDir" to dir.toString())))

            module.validate(first.analyzerId, createTestMergeRequest(), JsonObject(emptyMap()))

            assertEquals(1, provider.requests.size)
            assertTrue(!SystemFileSystem.exists(dir), "dump directory created with debug.prompt off")
            module.shutdown()
        }

    private suspend fun initialized(extra: Map<String, Any>): AiRuleModule {
        val analyzers = listOf(first, second)
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun aiAnalyzers(): List<AiAnalyzerModule> = analyzers

            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzers.find { it.analyzerId == analyzerId }

            override fun aiProviders(): List<AiProviderModule> = listOf(provider)

            override fun findAiProvider(providerId: String): AiProviderModule? = provider.takeIf { providerId == FAKE }
        }
        val module = AiRuleModule(registry)
        val config = mapOf("provider" to FAKE, "model" to BASE_MODEL, "maxTokenBudget" to BUDGET) + extra
        assertTrue(module.initialize(context(config)))
        return module
    }

    private fun context(config: Map<String, Any>) =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)
            override val logger: Logger = object : Logger {
                override fun debug(message: String) = Unit

                override fun info(message: String) = Unit

                override fun warn(message: String) = Unit

                override fun error(message: String) = Unit
            }

            override fun env(name: String): String? = null

            override fun log(message: String) = Unit

            override fun warn(message: String) = Unit

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    /** An analyzer that makes one provider call with a long system prompt and records its tokens. */
    private class ProbeAnalyzer(
        override val analyzerId: String,
    ) : AiAnalyzerModule {
        var calls = 0
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 100

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            calls++
            val request =
                AiCompletionRequest(
                    prompt = "review",
                    systemPrompt = "s".repeat(LONG_PROMPT),
                    model = context.model,
                    maxTokens = ASKED_TOKENS
                )
            val response = context.aiService.complete(request, null)
            context.budget.recordSpend(response.totalTokens)
            return listOf(createTestWarning(ruleId = analyzerId, message = "$analyzerId finding"))
        }
    }

    private class RecordingProvider : AiProviderModule {
        val requests = mutableListOf<AiCompletionRequest>()
        override val providerId: String = FAKE
        override val id: String = "io.karandash.ai.provider.$FAKE"
        override val name: String = FAKE
        override val version: String = "test"
        override val description: String = FAKE
        override val defaultModel: String = "base-m"
        override val apiKeyEnvVar: String? = null
        override val requiresApiKey: Boolean = false

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
            requests += request
            return AiCompletionResponse(
                """{"findings": []}""",
                REPLY_PROMPT_TOKENS,
                REPLY_COMPLETION_TOKENS,
                REPLY_TOTAL_TOKENS,
                request.model
            )
        }
    }

    private companion object {
        const val FAKE = "fake"
        const val PRICED_MODEL = "gpt-4o"
        const val BASE_MODEL = "gpt-4o-mini"
        const val BUDGET = 1_000_000
        const val PROMPT_CAP = 10
        const val LONG_PROMPT = 500
        const val ASKED_TOKENS = 4096
        const val OVERRIDE_TOKENS = 321
        const val CAP_USD = 0.001
        const val REPLY_PROMPT_TOKENS = 90_000
        const val REPLY_COMPLETION_TOKENS = 10_000
        const val REPLY_TOTAL_TOKENS = 100_000
    }
}
