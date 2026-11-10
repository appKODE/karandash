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
import io.karandash.plugins.ai.core.config.ChunkingConfig
import io.karandash.plugins.ai.core.pipeline.ReviewSamplingConfig
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

class AiRuleDispatcherChunkingConfigTest {
    @Test
    fun `ai chunking chunkSize splits the diff into one pass per chunk`() =
        runTest {
            assertEquals(listOf(1, 1, 1), passes(ChunkingConfig(chunkSize = DIFF.length, overlapSize = 0)))
        }

    @Test
    fun `ai chunking maxChunks caps the number of passes`() =
        runTest {
            assertEquals(2, passes(ChunkingConfig(chunkSize = DIFF.length, overlapSize = 0, maxChunks = 2)).size)
        }

    @Test
    fun `ai chunking disabled reviews the diff in a single pass`() =
        runTest {
            assertEquals(listOf(3), passes(ChunkingConfig(enabled = false, chunkSize = 1)))
        }

    @Test
    fun `without ai chunking the ai limits decide`() =
        runTest {
            assertEquals(listOf(3), passes(null))
        }

    @Test
    fun `ai chunking overlapSize repeats hunks across neighbouring passes`() =
        runTest {
            val hunkSize = HUNK.length
            val big = change("Big.kt", (1..6).joinToString("\n") { HUNK })
            val without = diffChars(ChunkingConfig(chunkSize = hunkSize * 2 + 2, overlapSize = 0), big)
            val with = diffChars(ChunkingConfig(chunkSize = hunkSize * 2 + 2, overlapSize = hunkSize), big)
            assertTrue(with.sum() > without.sum(), "$with vs $without")
        }

    /** File counts of each analyzer pass. */
    private suspend fun passes(chunking: ChunkingConfig?): List<Int> = run(chunking).passes

    private suspend fun diffChars(
        chunking: ChunkingConfig?,
        vararg changes: FileChange,
    ): List<Int> = run(chunking, changes.toList()).diffChars

    private suspend fun run(
        chunking: ChunkingConfig?,
        changes: List<FileChange> = listOf(change("A.kt"), change("B.kt"), change("C.kt")),
    ): CountingAnalyzer {
        val analyzer = CountingAnalyzer()
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == RULE }
        }
        val mr = createTestMergeRequest(changes = changes)
        AiRuleDispatcher(registry, NoOpLogger).dispatch(
            AiRuleDispatcher.DispatchInput(
                ruleId = RULE,
                mr = mr,
                concurrencySemaphore = Semaphore(1),
                aiProvider = UnusedProvider,
                budget = TokenBudget(maxTokens = 1_000_000),
                model = "m",
                config = JsonObject(emptyMap()),
                resolvedPrompt = null,
                aiService = UnusedService,
                aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = 1_000_000),
                pluginCfg = AiPluginConfig(chunking = chunking, reviewSampling = ReviewSamplingConfig(samples = 1)),
                contextProcessors = emptyList(),
                accumulatedFindings = emptyList(),
                customRules = emptyList(),
            ),
        )
        return analyzer
    }

    private fun change(
        path: String,
        diff: String = DIFF,
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = diff)

    private class CountingAnalyzer : AiAnalyzerModule {
        val passes = mutableListOf<Int>()
        val diffChars = mutableListOf<Int>()
        override val analyzerId: String = RULE
        override val id: String = RULE
        override val name: String = RULE
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = RULE
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 0

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            passes += context.mr.changes.size
            diffChars += context.mr.changes.sumOf { it.diffContent.orEmpty().length }
            return emptyList()
        }
    }

    private object UnusedService : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = error("the counting analyzer never calls the service")
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
        const val HUNK = "@@ -1,1 +1,1 @@\n-old line\n+new line plus padding to make the hunk longer"
    }
}
