package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A call runs while the run budget has room; half retries hold the run budget only, refusals name the knob. */
class AiRuleDispatcherBudgetGateTest {
    @Test
    fun `a small MR whose context exceeds its grant makes 1 call while the run budget has room`() =
        runTest {
            val service = Counting(cappedCalls = 0)
            val logger = WarnLogger()

            val result = dispatcher(logger).dispatch(
                input(service, rule = TokenBudget(maxTokens = 1), run = TokenBudget(maxTokens = 1_000_000), context = 50_000),
            )

            assertEquals(1, service.calls)
            assertEquals(emptyList(), result.refusals)
            assertEquals(emptyList(), result.skippedChunks)
        }

    @Test
    fun `a call no budget can hold is refused and recorded with its knob`() =
        runTest {
            val service = Counting(cappedCalls = 0)

            val result = dispatcher(WarnLogger()).dispatch(
                input(service, rule = TokenBudget(maxTokens = 1), run = TokenBudget(maxTokens = 1), context = 50_000),
            )

            assertEquals(0, service.calls)
            assertEquals(listOf("ai.large-mr.max-run-tokens"), result.refusals.map { it.knob })
        }

    @Test
    fun `a capped reply makes 2 half calls past the rule gate on the run budget`() =
        runTest {
            val service = Counting(cappedCalls = 1)
            val logger = WarnLogger()

            val result = dispatcher(logger).dispatch(
                input(service, rule = TokenBudget(maxTokens = 1), run = TokenBudget(maxTokens = 1_000_000)),
            )

            assertEquals(3, service.calls)
            assertEquals(emptyList(), result.refusals)
            assertTrue(logger.warnings.any { "retrying the diff as two halves" in it }, "${logger.warnings}")
        }

    @Test
    fun `a refused half retry warns naming the knob and is recorded in the budget plan`() =
        runTest {
            val service = Counting(cappedCalls = 1)
            val logger = WarnLogger()

            val result = dispatcher(logger).dispatch(
                input(service, rule = TokenBudget(maxTokens = 1_000_000), run = TokenBudget(maxTokens = 1), gateRun = false),
            )

            assertEquals(1, service.calls)
            assertEquals(2, result.refusals.size)
            assertEquals(setOf("half retry after a capped reply"), result.refusals.map { it.reason }.toSet())
            assertEquals(setOf("ai.large-mr.max-run-tokens"), result.refusals.map { it.knob }.toSet())
            assertTrue(
                logger.warnings.any { "half retry after a capped reply refused" in it && it.endsWith("raise ai.large-mr.max-run-tokens") },
                "${logger.warnings}",
            )
        }

    private fun dispatcher(logger: Logger): AiRuleDispatcher {
        val analyzer = CallingAnalyzer()
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == it.analyzerId }
        }
        return AiRuleDispatcher(registry, logger)
    }

    private fun input(
        service: AiService,
        rule: TokenBudget,
        run: TokenBudget,
        context: Int = 0,
        gateRun: Boolean = true,
    ) = AiRuleDispatcher.DispatchInput(
        ruleId = AiRuleIds.REVIEW_COMMENTS,
        mr = createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt"))),
        concurrencySemaphore = Semaphore(1),
        aiProvider = UnusedProvider,
        budget = rule,
        model = "m",
        config = JsonObject(emptyMap()),
        resolvedPrompt = null,
        aiService = service,
        aiRuleConfig = AiRuleConfig(),
        pluginCfg = AiPluginConfig(structuredOutput = false, reviewSampling = ReviewSamplingConfig(samples = 1)),
        contextProcessors = emptyList(),
        accumulatedFindings = emptyList(),
        customRules = emptyList(),
        runBudget = run,
        gateRun = gateRun,
        plannedContextTokens = context,
    )

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 1, diffContent = "@@ -1 +1 @@\n-a\n+b")

    /** Counts calls; the first [cappedCalls] replies fill their output cap. */
    private class Counting(
        private val cappedCalls: Int,
    ) : AiService {
        var calls = 0
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            calls++
            val out = if (calls <= cappedCalls) request.maxTokens else 1
            return AiCompletionResponse("[]", 1, out, out + 1, request.model)
        }
    }

    private class CallingAnalyzer : AiAnalyzerModule {
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
            return emptyList()
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

    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }
}
