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
import io.karandash.plugins.ai.core.DiffContextBuilder
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rules dispatched at once on the same MR and settings share one diff-context build. */
class AiRuleDispatcherDiffContextCacheTest {
    @Test
    fun `concurrent rules with the same diff context key build it once`() =
        runTest {
            val builds = MutableStateFlow(0)
            val analyzer = SilentAnalyzer(RULE)
            val registry = object : ModuleRegistry by buildTestRegistry() {
                override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == RULE }
            }
            val dispatcher = AiRuleDispatcher(registry, NoOpLogger) { input, quality ->
                builds.update { it + 1 }
                delay(BUILD_MILLIS)
                DiffContextBuilder.build(input.mr, input.aiRuleConfig, quality, input.enrichment, null, input.pluginCfg.reviewMode)
            }
            val change = FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, additions = 1, deletions = 1, diffContent = DIFF)
            val input = AiRuleDispatcher.DispatchInput(
                ruleId = RULE,
                mr = createTestMergeRequest(changes = listOf(change)),
                concurrencySemaphore = Semaphore(RULES),
                aiProvider = UnusedProvider,
                budget = TokenBudget(maxTokens = 100_000),
                model = "m",
                config = JsonObject(emptyMap()),
                resolvedPrompt = null,
                aiService = UnusedService,
                aiRuleConfig = AiRuleConfig(),
                pluginCfg = AiPluginConfig(),
                contextProcessors = emptyList(),
                accumulatedFindings = emptyList(),
                customRules = emptyList(),
            )

            coroutineScope { repeat(RULES) { launch { dispatcher.dispatch(input) } } }

            assertEquals(1, builds.value)
        }

    private class SilentAnalyzer(
        override val analyzerId: String,
    ) : AiAnalyzerModule {
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 0

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = emptyList()
    }

    private object UnusedService : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = error("the silent analyzer never calls the service")
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
        const val RULE = "review-comments"
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
        const val RULES = 4
        const val BUILD_MILLIS = 100L
    }
}
