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
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
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

/** Review-comments sample 2 runs after every first call, on a budget of its own, and never costs coverage. */
class SampleBudgetTest {
    @Test
    fun `a sample budget too small for the samples keeps every chunk reviewed and unmarked`() =
        runTest {
            val service = Temperatures()
            // the rule's share holds no call: every first call runs on the run budget, the samples get nothing
            val result = dispatcher().dispatch(input(service, TokenBudget(1), TokenBudget(1_000_000)))

            assertEquals(CHUNKS, service.temperatures.size, "a chunk's first call did not run")
            assertTrue(result.skippedChunks.isEmpty(), result.skippedChunks.toString())
            assertTrue(result.findings.isNotEmpty())
            assertTrue(result.findings.all { AiFindingMetadataKeys.AGREEMENT !in it.metadata }, result.findings.toString())
            assertEquals(CHUNKS, result.refusals.count { it.reason == "extra samples" }, result.refusals.toString())
        }

    @Test
    fun `with ample budget every chunk takes sample 2 after all first calls and agreement applies`() =
        runTest {
            val service = Temperatures()
            val result = dispatcher().dispatch(input(service, TokenBudget(1_000_000), null))

            assertEquals(CHUNKS * 2, service.temperatures.size)
            assertTrue(service.temperatures.take(CHUNKS).none { it == SAMPLE_TEMPERATURE }, service.temperatures.toString())
            assertEquals(List(CHUNKS) { SAMPLE_TEMPERATURE }, service.temperatures.drop(CHUNKS))
            assertTrue(result.findings.isNotEmpty())
            assertTrue(
                result.findings.all { it.metadata[AiFindingMetadataKeys.AGREEMENT] == SampleAgreement.AGREED },
                result.findings.toString(),
            )
        }

    private fun dispatcher(): AiRuleDispatcher {
        val analyzer = FileCommenter()
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == it.analyzerId }
        }
        return AiRuleDispatcher(registry, NoOpLogger)
    }

    private fun input(
        service: AiService,
        budget: TokenBudget,
        runBudget: TokenBudget?,
    ) = AiRuleDispatcher.DispatchInput(
        ruleId = AiRuleIds.REVIEW_COMMENTS,
        mr = createTestMergeRequest(changes = (1..CHUNKS).map { change("F$it.kt") }),
        concurrencySemaphore = Semaphore(1),
        aiProvider = UnusedProvider,
        budget = budget,
        model = "m",
        config = JsonObject(emptyMap()),
        resolvedPrompt = null,
        aiService = service,
        aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = DIFF.length),
        pluginCfg = AiPluginConfig(structuredOutput = false),
        contextProcessors = emptyList(),
        accumulatedFindings = emptyList(),
        customRules = emptyList(),
        runBudget = runBudget,
    )

    private fun change(path: String) = FileChange(path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 1, diffContent = DIFF)

    /** Records each request's temperature in call order. */
    private class Temperatures : AiService {
        val temperatures = mutableListOf<Double>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            temperatures += request.temperature
            return AiCompletionResponse("[]", 1, 1, 2, request.model)
        }
    }

    /** Comments the same issue on every file of its call, so every sample agrees. */
    private class FileCommenter : AiAnalyzerModule {
        override val analyzerId: String = AiRuleIds.REVIEW_COMMENTS
        override val id: String = analyzerId
        override val name: String = analyzerId
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = analyzerId
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 0

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            context.aiService.complete(AiCompletionRequest(prompt = "review", model = "m"))
            return context.mr.changes.map {
                Finding(
                    severity = Severity.WARNING,
                    message = "Possible null dereference of user",
                    rule = AiRuleIds.REVIEW_COMMENTS,
                    location = Location.FileRef(it.path, 1),
                    metadata = mapOf(AiFindingMetadataKeys.CATEGORY to "BUG"),
                )
            }
        }
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
        const val CHUNKS = 3
        const val SAMPLE_TEMPERATURE = 0.5
        const val DIFF = "@@ -1,2 +1,2 @@\n-old line\n+new line\n context"
    }
}
