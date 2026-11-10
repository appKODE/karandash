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
import io.karandash.plugins.ai.core.config.AiBudgetConfig
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import io.karandash.plugins.ai.core.config.ChunkingConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.plugins.ai.rules.CustomAiRule
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The dispatcher is the one budget gate, and every budget skip lands in the coverage ledger. */
class AiRuleDispatcherTest {
    @Test
    fun `budget exhausted rule is recorded as skipped not silently empty`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)

            val result = AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(input(TokenBudget(maxTokens = 500)))

            assertEquals(0, analyzer.calls, "the gate must stop the call")
            assertEquals(RuleCoverage.BUDGET_EXHAUSTED, result.coverage?.skipReason)
            assertEquals(emptySet(), result.coverage?.reviewed)
        }

    @Test
    fun `rule within budget runs and is recorded as reviewed`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)

            val result = AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(input(TokenBudget(maxTokens = 5_000)))

            assertEquals(1, analyzer.calls)
            assertEquals(null, result.coverage?.skipReason)
            assertEquals(setOf("A.kt"), result.coverage?.reviewed)
        }

    @Test
    fun `exhausted run budget skips a rule whose own budget still has room`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)
            val run = TokenBudget(maxTokens = 500)

            val result = AiRuleDispatcher(registryWith(analyzer), NoOpLogger)
                .dispatch(input(TokenBudget(maxTokens = 5_000)).copy(runBudget = run))

            assertEquals(0, analyzer.calls)
            assertEquals(RuleCoverage.BUDGET_EXHAUSTED, result.coverage?.skipReason)
        }

    @Test
    fun `custom rule over budget is recorded as skipped`() =
        runTest {
            val custom = CustomAiRule(id = "my-rule", name = "My rule", prompt = "check it")

            val result = AiRuleDispatcher(buildTestRegistry(), NoOpLogger).dispatch(
                input(TokenBudget(maxTokens = 10)).copy(ruleId = "my-rule", customRules = listOf(custom)),
            )

            assertEquals(emptyList(), result.findings)
            assertEquals(RuleCoverage.BUDGET_EXHAUSTED, result.coverage?.skipReason)
        }

    @Test
    fun `T0 over budget rule is recorded as skipped with its reason`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)
            val reason = "T0 over budget: conventions is 9000 tokens, T0 is 9500 of ai.context.budget 8000"

            val result = AiRuleDispatcher(registryWith(analyzer), NoOpLogger)
                .dispatch(input(TokenBudget(maxTokens = 5_000)).copy(tierSkip = reason))

            assertEquals(0, analyzer.calls, "a T0 overflow never reaches the model")
            assertEquals(reason, result.coverage?.skipReason)
        }

    @Test
    fun `diff files the budget left out and dropped context land in the ledger`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)
            val mr = createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt")))
            val tight = input(TokenBudget(maxTokens = 5_000)).copy(
                mr = mr,
                aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = DIFF.length + 2, maxDiffCharsPerFile = DIFF.length + 2),
                pluginCfg = AiPluginConfig(chunking = ChunkingConfig(enabled = false)),
                contextDropped = listOf("ci-context"),
            )

            val coverage = AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(tight).coverage

            assertEquals(1, analyzer.calls)
            assertEquals(setOf("A.kt"), coverage?.reviewed)
            assertEquals(setOf("B.kt"), coverage?.dropped)
            assertEquals(setOf("ci-context"), coverage?.contextDropped)
        }

    @Test
    fun `files past the chunk limit are dropped in the ledger not left unreviewed silently`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)
            val mr = createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt"), change("C.kt")))
            val capped = input(TokenBudget(maxTokens = 50_000)).copy(
                mr = mr,
                aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = DIFF.length, maxDiffCharsPerFile = DIFF.length, maxChunks = 2),
            )

            val coverage = AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(capped).coverage

            assertEquals(2, analyzer.calls)
            assertEquals(setOf("A.kt", "B.kt"), coverage?.reviewed)
            assertEquals(setOf("C.kt"), coverage?.dropped)
        }

    @Test
    fun `hunk windows past the chunk limit are dropped in the ledger and the file stays reviewed`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)
            val hunks = (1..8).joinToString("\n") { "@@ -$it,1 +$it,1 @@\n-old $it\n+new $it" }
            val big = change("Big.kt").copy(diffContent = hunks)
            val capped = input(TokenBudget(maxTokens = 50_000)).copy(
                mr = createTestMergeRequest(changes = listOf(big)),
                pluginCfg = AiPluginConfig(chunking = ChunkingConfig(chunkSize = HUNK_CHARS, overlapSize = 0, maxChunks = 3)),
            )

            val coverage = AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(capped).coverage

            assertEquals(3, analyzer.calls)
            assertEquals(setOf("Big.kt"), coverage?.reviewed)
            assertEquals(setOf("Big.kt (5 of 8 hunks)"), coverage?.dropped)
        }

    @Test
    fun `a T0 skip on a description-only MR keeps its ledger entry`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)
            val reason = "T0 over budget: ticket-content is 9000 tokens, T0 is 9500 of ai.context.budget 8000"
            val noDiff = input(TokenBudget(maxTokens = 5_000)).copy(mr = createTestMergeRequest(changes = emptyList()), tierSkip = reason)

            val coverage = AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(noDiff).coverage

            assertEquals(reason, coverage?.skipReason)
        }

    @Test
    fun `a budget skip on a description-only MR keeps its ledger entry`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)
            val noDiff = input(TokenBudget(maxTokens = 500)).copy(mr = createTestMergeRequest(changes = emptyList()))

            val coverage = AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(noDiff).coverage

            assertEquals(0, analyzer.calls)
            assertEquals(RuleCoverage.BUDGET_EXHAUSTED, coverage?.skipReason)
        }

    @Test
    fun `a description-only MR that runs reports no coverage`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)
            val noDiff = input(TokenBudget(maxTokens = 5_000)).copy(mr = createTestMergeRequest(changes = emptyList()))

            val result = AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(noDiff)

            assertEquals(1, analyzer.calls)
            assertEquals(null, result.coverage)
        }

    @Test
    fun `a planned prompt over the remaining budget is skipped although the static estimate fits`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 100)
            val big = input(TokenBudget(maxTokens = 1_000)).copy(plannedContextTokens = 5_000)

            val coverage = AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(big).coverage

            assertEquals(0, analyzer.calls, "the gate reserves the planned prompt, not just the estimate")
            assertEquals(RuleCoverage.BUDGET_EXHAUSTED, coverage?.skipReason)
        }

    @Test
    fun `a planned prompt within the remaining budget runs`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 100)
            val fits = input(TokenBudget(maxTokens = 1_000)).copy(plannedContextTokens = 500)

            AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(fits)

            assertEquals(1, analyzer.calls)
        }

    @Test
    fun `parallel rules never spend above the run budget`() =
        runTest {
            val run = TokenBudget(maxTokens = 2_500)
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000, spend = 1_000)
            val dispatcher = AiRuleDispatcher(registryWith(analyzer), NoOpLogger)
            val parallel = input(run).copy(concurrencySemaphore = Semaphore(PARALLEL_RULES))

            coroutineScope { repeat(PARALLEL_RULES) { launch { dispatcher.dispatch(parallel) } } }

            assertEquals(2, analyzer.calls)
            assertTrue(run.totalConsumed() <= 2_500, "spent ${run.totalConsumed()} of 2500")
        }

    @Test
    fun `parallel rules within the run budget all run`() =
        runTest {
            val run = TokenBudget(maxTokens = 10_000)
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000, spend = 1_000)
            val dispatcher = AiRuleDispatcher(registryWith(analyzer), NoOpLogger)
            val parallel = input(run).copy(concurrencySemaphore = Semaphore(PARALLEL_RULES))

            coroutineScope { repeat(PARALLEL_RULES) { launch { dispatcher.dispatch(parallel) } } }

            assertEquals(PARALLEL_RULES, analyzer.calls)
            assertEquals(PARALLEL_RULES * 1_000, run.totalConsumed())
        }

    @Test
    fun `the chunk loop stops once real spend leaves no room and records the rest`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 0, spend = 1_000)
            val mr = createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt"), change("C.kt")))
            val chunked = input(TokenBudget(maxTokens = 2_005)).copy(
                mr = mr,
                aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = DIFF.length, maxDiffCharsPerFile = DIFF.length),
                pluginCfg = AiPluginConfig(budget = TINY_RESERVE, largeMr = AiLargeMrConfig(scaleBudget = false)),
            )

            val coverage = AiRuleDispatcher(registryWith(analyzer), NoOpLogger).dispatch(chunked).coverage

            assertEquals(2, analyzer.calls)
            assertEquals(1, coverage?.chunksSkipped)
            assertEquals(2, coverage?.reviewed?.size)
        }

    @Test
    fun `a diff that fits whole drops no file`() =
        runTest {
            val analyzer = CountingAnalyzer(estimatedTokens = 1_000)
            val mr = createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt")))

            val coverage = AiRuleDispatcher(registryWith(analyzer), NoOpLogger)
                .dispatch(input(TokenBudget(maxTokens = 5_000)).copy(mr = mr))
                .coverage

            assertEquals(setOf("A.kt", "B.kt"), coverage?.reviewed)
            assertEquals(emptySet(), coverage?.dropped)
        }

    private fun registryWith(analyzer: CountingAnalyzer): ModuleRegistry =
        object : ModuleRegistry by buildTestRegistry() {
            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { analyzerId == RULE }
        }

    private fun input(budget: TokenBudget) =
        AiRuleDispatcher.DispatchInput(
            ruleId = RULE,
            mr = createTestMergeRequest(changes = listOf(change("A.kt"))),
            concurrencySemaphore = Semaphore(1),
            aiProvider = UnusedProvider,
            budget = budget,
            model = "m",
            config = JsonObject(emptyMap()),
            resolvedPrompt = null,
            aiService = SpendingService,
            aiRuleConfig = AiRuleConfig(),
            pluginCfg = AiPluginConfig(budget = TINY_RESERVE),
            contextProcessors = emptyList(),
            accumulatedFindings = emptyList(),
            customRules = emptyList(),
        )

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = DIFF)

    /** Returns nothing without calling `context.skip`, like a third-party analyzer that skips silently. */
    private class CountingAnalyzer(
        override val estimatedTokens: Int,
        private val spend: Int = 0,
    ) : AiAnalyzerModule {
        private val seen = mutableListOf<String>()
        val calls: Int get() = seen.size
        override val analyzerId: String = RULE
        override val id: String = RULE
        override val name: String = RULE
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = RULE
        override val defaultEnabled: Boolean = true

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            seen += context.mr.title
            delay(1)
            if (spend > 0) context.aiService.complete(AiCompletionRequest(prompt = "p", model = "m", maxTokens = spend))
            // the dispatcher records the completion's spend; the analyzer's own recordSpend must not count twice
            context.budget.recordSpend(spend)
            return emptyList()
        }
    }

    /** Answers with `totalTokens` equal to the request's `maxTokens`. */
    private object SpendingService : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = AiCompletionResponse("{}", 0, request.maxTokens, request.maxTokens, request.model)
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
        ): AiResponse = error("the budget gate runs before any completion")

        override suspend fun close() = Unit
    }

    private companion object {
        const val RULE = "risk-scoring"
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
        const val HUNK_CHARS = 30
        const val PARALLEL_RULES = 5

        /** A 1-token reply reserve, so the gate arithmetic here counts the prompt alone (0 means the 2000 default). */
        val TINY_RESERVE = AiBudgetConfig(reserveOutputTokens = 1)
    }
}
