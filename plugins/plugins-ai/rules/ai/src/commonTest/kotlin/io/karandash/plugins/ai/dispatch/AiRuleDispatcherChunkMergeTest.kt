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
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.pipeline.ReviewSamplingConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** how a chunked MR's analyzer calls and findings are merged. */
class AiRuleDispatcherChunkMergeTest {
    @Test
    fun `an mr-level analyzer runs once on a chunked MR with one finding`() =
        runTest {
            val analyzer = CountingAnalyzer(mrLevel = true)
            val findings = dispatch(analyzer)

            assertEquals(listOf(2), analyzer.calls, "one call that sees both files")
            assertEquals(1, findings.size)
        }

    @Test
    fun `a per-file analyzer still runs once per chunk`() =
        runTest {
            val analyzer = CountingAnalyzer(mrLevel = false)
            dispatch(analyzer)

            assertEquals(listOf(1, 1), analyzer.calls)
        }

    @Test
    fun `review-comments maxComments caps the merged findings of all chunks`() =
        runTest {
            val analyzer = CountingAnalyzer(mrLevel = false, ruleId = AiRuleIds.REVIEW_COMMENTS)
            val findings = dispatch(analyzer, buildJsonObject { put("maxComments", 1) })

            assertEquals(listOf(1, 1), analyzer.calls)
            assertEquals(listOf("B.kt is wrong"), findings.map { it.message }, "the more severe comment is kept")
        }

    private suspend fun dispatch(
        analyzer: CountingAnalyzer,
        config: JsonObject = JsonObject(emptyMap()),
    ): List<Finding> {
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == it.analyzerId }
        }
        return AiRuleDispatcher(registry, NoOpLogger)
            .dispatch(
                AiRuleDispatcher.DispatchInput(
                    ruleId = analyzer.analyzerId,
                    mr = createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt"))),
                    concurrencySemaphore = Semaphore(1),
                    aiProvider = UnusedProvider,
                    budget = TokenBudget(maxTokens = 100_000),
                    model = "m",
                    config = config,
                    resolvedPrompt = null,
                    aiService = UnusedService,
                    // Each file's diff exceeds the per-chunk size, so the MR splits into two chunks.
                    aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = DIFF.length + 10),
                    pluginCfg = AiPluginConfig(reviewSampling = ReviewSamplingConfig(samples = 1)),
                    contextProcessors = emptyList(),
                    accumulatedFindings = emptyList(),
                    customRules = emptyList(),
                ),
            ).findings
    }

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = DIFF)

    private class CountingAnalyzer(
        override val mrLevel: Boolean,
        ruleId: String = RULE,
    ) : AiAnalyzerModule {
        val calls = mutableListOf<Int>()
        override val analyzerId: String = ruleId
        override val id: String = ruleId
        override val name: String = ruleId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val defaultEnabled: Boolean = true

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            calls += context.mr.changes.size
            if (analyzerId == RULE) return listOf(Finding(rule = analyzerId, severity = Severity.INFO, message = "MR verdict"))
            // One comment per chunk; the one on B.kt is the more severe.
            return context.mr.changes.map { change ->
                Finding(
                    rule = analyzerId,
                    severity = if (change.path == "B.kt") Severity.ERROR else Severity.WARNING,
                    message = "${change.path} is wrong",
                    location = Location.FileRef(change.path, 1),
                )
            }
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
        const val RULE = "verdict"
        val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line\n".repeat(20)
    }
}
