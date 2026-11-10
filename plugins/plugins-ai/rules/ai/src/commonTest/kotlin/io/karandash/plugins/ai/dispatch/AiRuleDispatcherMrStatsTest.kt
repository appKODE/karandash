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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MR metadata given to analyzers states whole-MR file statistics even for a filtered, chunked run. */
class AiRuleDispatcherMrStatsTest {
    @Test
    fun `chunked analyzer metadata reports whole MR file count`() =
        runTest {
            val analyzer = RecordingAnalyzer("risk-scoring")
            val registry = object : ModuleRegistry by buildTestRegistry() {
                override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == "risk-scoring" }
            }
            val fullMr = createTestMergeRequest(changes = (1..5).map { change("F$it.kt") })
            val filtered = fullMr.copy(changes = fullMr.changes.take(2))

            AiRuleDispatcher(registry, NoOpLogger).dispatch(input(filtered, fullMr))

            assertEquals(2, analyzer.metadata.size, "expected one call per chunk")
            analyzer.metadata.forEach { meta ->
                assertTrue("Files changed: 5 (in this review: 1)" in meta, meta)
                assertTrue("Additions: 5" in meta, meta)
            }
        }

    @Test
    fun `unfiltered single chunk metadata keeps the plain file count`() =
        runTest {
            val analyzer = RecordingAnalyzer("risk-scoring")
            val registry = object : ModuleRegistry by buildTestRegistry() {
                override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == "risk-scoring" }
            }
            val mr = createTestMergeRequest(changes = listOf(change("A.kt")))

            AiRuleDispatcher(registry, NoOpLogger).dispatch(input(mr, mr).copy(aiRuleConfig = AiRuleConfig()))

            assertTrue("Files changed: 1\n" in analyzer.metadata.single(), analyzer.metadata.single())
        }

    private fun input(
        mr: io.karandash.models.MergeRequest,
        fullMr: io.karandash.models.MergeRequest,
    ) = AiRuleDispatcher.DispatchInput(
        ruleId = "risk-scoring",
        mr = mr,
        concurrencySemaphore = Semaphore(1),
        aiProvider = UnusedProvider,
        budget = TokenBudget(maxTokens = 100_000),
        model = "m",
        config = JsonObject(emptyMap()),
        resolvedPrompt = null,
        aiService = UnusedService,
        // Each file's diff exceeds the per-chunk size, so every file becomes its own chunk.
        aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = DIFF.length),
        pluginCfg = AiPluginConfig(),
        contextProcessors = emptyList(),
        accumulatedFindings = emptyList(),
        customRules = emptyList(),
        fullMr = fullMr,
    )

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = DIFF)

    private class RecordingAnalyzer(
        override val analyzerId: String,
    ) : AiAnalyzerModule {
        val metadata = mutableListOf<String>()
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 0

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            metadata += context.diffContext.metadata
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
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
    }
}
