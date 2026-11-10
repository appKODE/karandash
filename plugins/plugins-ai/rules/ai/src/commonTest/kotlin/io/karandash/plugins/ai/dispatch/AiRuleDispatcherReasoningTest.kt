package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiRuleDispatcherReasoningTest {
    @Test
    fun `per-analyzer reasoning overrides the global reasoning for that rule only`() =
        runTest {
            val cfg = AiPluginConfig(reasoning = "direct", reasonings = mapOf("reviewComments" to "semi-formal"))

            assertTrue(CERTIFICATE in promptFor("review-comments", cfg))
            assertFalse(CERTIFICATE in promptFor("code-suggestions", cfg))
        }

    @Test
    fun `global reasoning applies when no per-analyzer override is set`() =
        runTest {
            assertTrue(CERTIFICATE in promptFor("review-comments", AiPluginConfig(reasoning = "semi-formal")))
            assertFalse(CERTIFICATE in promptFor("review-comments", AiPluginConfig()))
        }

    @Test
    fun `strictness low uses DirectStrategy over the global reasoning`() =
        runTest {
            val low = AiPluginConfig(reasoning = "semi-formal", strictness = "low")

            assertFalse(CERTIFICATE in promptFor("review-comments", low))
            assertTrue(CERTIFICATE in promptFor("review-comments", low.copy(reasonings = mapOf("reviewComments" to "semi-formal"))))
        }

    /** The prompt the dispatcher hands the analyzer for [ruleId] (empty when it passes none). */
    private suspend fun promptFor(
        ruleId: String,
        cfg: AiPluginConfig,
    ): String {
        val analyzer = RecordingAnalyzer(ruleId)
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == ruleId }
        }
        val change = FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = DIFF)
        AiRuleDispatcher(registry, NoOpLogger).dispatch(
            AiRuleDispatcher.DispatchInput(
                ruleId = ruleId,
                mr = createTestMergeRequest(changes = listOf(change)),
                concurrencySemaphore = Semaphore(1),
                aiProvider = UnusedProvider,
                budget = TokenBudget(maxTokens = 100_000),
                model = "m",
                config = JsonObject(emptyMap()),
                resolvedPrompt = null,
                aiService = UnusedService,
                aiRuleConfig = AiRuleConfig(),
                pluginCfg = cfg,
                contextProcessors = emptyList(),
                accumulatedFindings = emptyList(),
                customRules = emptyList(),
            ),
        )
        return checkNotNull(analyzer.prompt) { "analyzer was not called for $ruleId" }
    }

    private class RecordingAnalyzer(
        override val analyzerId: String,
    ) : AiAnalyzerModule {
        var prompt: String? = null
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 0
        override val supportsSemiFormalReasoning: Boolean = true

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            prompt = context.resolvedPrompt.orEmpty()
            return emptyList()
        }
    }

    private object UnusedService : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = error("the recording analyzer never calls the service")
    }

    private object UnusedProvider : AiProvider {
        override val providerId = "unused"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse = error("analyzers call through AiService")

        override suspend fun close() = Unit
    }

    private companion object {
        const val CERTIFICATE = "SEMI-FORMAL REASONING CERTIFICATE"
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
    }
}
