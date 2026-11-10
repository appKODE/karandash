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
import io.karandash.plugins.ai.core.DiffPreprocessorOptions
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

/** Files the diff preprocessor drops whole are recorded as excluded, apart from what the budget left out. */
class AiRuleDispatcherExclusionTest {
    @Test
    fun `a deleted file the preprocessor drops is excluded not left out to fit the budget`() =
        runTest {
            val coverage = coverageOf(DiffPreprocessorOptions(skipDeletedFiles = true))

            assertEquals(emptySet(), coverage.dropped)
            assertEquals(setOf("C.kt (deleted)"), coverage.excluded)
            assertEquals(setOf("A.kt"), coverage.reviewed)
        }

    @Test
    fun `a deleted file the preprocessor keeps is reviewed and not excluded`() =
        runTest {
            val coverage = coverageOf(DiffPreprocessorOptions(skipDeletedFiles = false))

            assertEquals(emptySet(), coverage.excluded)
            assertEquals(setOf("A.kt", "C.kt"), coverage.reviewed)
        }

    @Test
    fun `files preprocessing left out before dispatch are excluded in the ledger`() =
        runTest {
            val coverage = coverageOf(
                DiffPreprocessorOptions(skipDeletedFiles = true),
                excluded = mapOf("Cargo.lock" to "matched **/*.lock"),
            )

            assertEquals(setOf("C.kt (deleted)", "Cargo.lock (matched **/*.lock)"), coverage.excluded)
            assertEquals(setOf("A.kt"), coverage.reviewed)
        }

    private suspend fun coverageOf(
        options: DiffPreprocessorOptions,
        excluded: Map<String, String> = emptyMap(),
    ): RuleCoverage {
        val analyzer = SilentAnalyzer(RULE)
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == RULE }
        }
        val a = FileChange(path = "A.kt", changeType = ChangeType.MODIFIED, additions = 1, deletions = 1, diffContent = DIFF)
        val c =
            FileChange(path = "C.kt", changeType = ChangeType.DELETED, additions = 0, deletions = 1, diffContent = "@@ -1,1 +0,0 @@\n-gone")
        val result = AiRuleDispatcher(registry, NoOpLogger).dispatch(
            AiRuleDispatcher.DispatchInput(
                ruleId = RULE,
                mr = createTestMergeRequest(changes = listOf(a, c)),
                concurrencySemaphore = Semaphore(1),
                aiProvider = UnusedProvider,
                budget = TokenBudget(maxTokens = 100_000),
                model = "m",
                config = JsonObject(emptyMap()),
                resolvedPrompt = null,
                aiService = UnusedService,
                aiRuleConfig = AiRuleConfig(diffPreprocessor = options),
                pluginCfg = AiPluginConfig(),
                contextProcessors = emptyList(),
                accumulatedFindings = emptyList(),
                customRules = emptyList(),
                excluded = excluded,
            ),
        )
        return checkNotNull(result.coverage) { "the rule ran on a diff, so it reports coverage" }
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
    }
}
