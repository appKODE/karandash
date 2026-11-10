package io.karandash.plugins.ai.dispatch

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.LargeMrSummary
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.ModelPrice
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.config.AiBudgetConfig
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import io.karandash.plugins.ai.core.config.CostBudgetConfig
import io.karandash.plugins.ai.core.rules.AiExcludePolicy
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.plugins.ai.rules.CustomAiRule
import io.karandash.plugins.ai.rules.CustomAiRuleExecutor
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The chunks of one rule hold their budget in chunk order before any is sent, so the same chunks always fit. */
class AiRuleDispatcherBudgetDeterminismTest {
    private class JitterProvider(
        private val cancelOn: String? = null,
        private val failOn: String? = null,
    ) : AiProvider {
        override val providerId = "fake"
        private val lock = Mutex()
        val prompts = mutableListOf<String>()

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse {
            delay(Random.nextLong(1, MAX_JITTER_MS))
            lock.withLock { prompts += "$systemPrompt\n---\n$prompt" }
            if (cancelOn != null && cancelOn in prompt) throw CancellationException("cancelled")
            check(failOn == null || failOn !in prompt) { "provider failed" }
            return AiResponse("{}", SPEND, 0, SPEND, model)
        }

        override suspend fun close() = Unit
    }

    private suspend fun dispatch(
        provider: AiProvider,
        budget: TokenBudget,
        paths: List<String> = PATHS,
        pluginCfg: AiPluginConfig =
            AiPluginConfig(budget = TINY_RESERVE, largeMr = AiLargeMrConfig(maxParallel = PARALLEL, scaleBudget = false)),
    ) = AiRuleDispatcher(buildTestRegistry(), NoOpLogger).dispatch(
        AiRuleDispatcher.DispatchInput(
            ruleId = RULE_ID,
            mr = mr(paths),
            concurrencySemaphore = Semaphore(PERMITS),
            aiProvider = provider,
            budget = budget,
            model = "m",
            config = JsonObject(emptyMap()),
            resolvedPrompt = null,
            aiService = null,
            aiRuleConfig = ruleConfig(paths),
            pluginCfg = pluginCfg,
            contextProcessors = emptyList(),
            accumulatedFindings = emptyList(),
            customRules = CUSTOM_RULES,
        ),
    )

    private fun mr(paths: List<String>) = createTestMergeRequest(changes = paths.map(::change))

    // A chunk holds one file's diff and no more, so the MR splits into one chunk per file.
    private fun ruleConfig(paths: List<String>) = AiRuleConfig(maxDiffCharsTotal = DIFF.length, maxChunks = paths.size)

    /** The per-MR plan grows the rule's [share] before it dispatches, against a run budget of one hold. */
    private fun planned(
        share: TokenBudget,
        pluginCfg: AiPluginConfig,
    ): TokenBudget {
        val input = ChunkBudget.Input(
            mapOf(RULE_ID to share),
            TokenBudget(maxTokens = HOLD),
            mr(PATHS),
            pluginCfg,
            ruleConfig(PATHS),
            "m",
            CUSTOM_RULES
        )
        ChunkBudget(buildTestRegistry()).plan(input, NoOpLogger)
        return share
    }

    @Test
    fun `a budget that fits two chunks always reviews the first two and skips the same tail`() =
        runTest {
            val ledgers = withContext(Dispatchers.Default) {
                List(RUNS) { dispatch(JitterProvider(), TokenBudget(maxTokens = 2 * HOLD)).coverage }
            }

            ledgers.forEach { coverage ->
                assertEquals(setOf("A.kt", "B.kt"), coverage?.reviewed)
                assertEquals(PATHS.size - 2, coverage?.chunksSkipped)
            }
            assertEquals(List(RUNS) { ledgers.first() }, ledgers)
        }

    @Test
    fun `scale budget under a cost budget gives every chunk its own share so the whole MR is reviewed`() =
        runTest {
            val scaled =
                AiPluginConfig(budget = TINY_RESERVE, largeMr = AiLargeMrConfig(maxParallel = PARALLEL), costBudget = CostBudgetConfig())

            val coverage = dispatch(JitterProvider(), planned(TokenBudget(maxTokens = HOLD), scaled), pluginCfg = scaled).coverage

            assertEquals(PATHS.toSet(), coverage?.reviewed)
            assertEquals(0, coverage?.chunksSkipped)
        }

    @Test
    fun `the cost budget token cap bounds the grown budget`() =
        runTest {
            val capped = AiPluginConfig(
                budget = TINY_RESERVE,
                largeMr = AiLargeMrConfig(maxParallel = PARALLEL),
                costBudget = CostBudgetConfig(maxTokensPerRun = 3 * HOLD),
            )

            val coverage = dispatch(JitterProvider(), planned(TokenBudget(maxTokens = HOLD), capped), pluginCfg = capped).coverage

            assertEquals(setOf("A.kt", "B.kt", "C.kt"), coverage?.reviewed)
            assertEquals(PATHS.size - 3, coverage?.chunksSkipped)
        }

    @Test
    fun `the cost budget USD cap bounds the grown budget`() =
        runTest {
            // One USD buys one input token, so the USD cap is the same 3 holds as the token cap above.
            val capped = AiPluginConfig(
                budget = TINY_RESERVE,
                largeMr = AiLargeMrConfig(maxParallel = PARALLEL),
                costBudget = CostBudgetConfig(maxCostPerRunUsd = 3.0 * HOLD),
                pricing = mapOf("m" to ModelPrice(input = 1_000_000.0, output = 1_000_000.0)),
            )

            val coverage = dispatch(JitterProvider(), planned(TokenBudget(maxTokens = HOLD), capped), pluginCfg = capped).coverage

            assertEquals(setOf("A.kt", "B.kt", "C.kt"), coverage?.reviewed)
            assertEquals(PATHS.size - 3, coverage?.chunksSkipped)
        }

    @Test
    fun `every hold is returned after a parallel run and only the actual spend stays charged`() =
        runTest {
            val budget = TokenBudget(maxTokens = 2 * HOLD)

            withContext(Dispatchers.Default) { dispatch(JitterProvider(), budget) }

            assertEquals(2 * SPEND, budget.totalConsumed())
        }

    @Test
    fun `a cancelled chunk propagates and returns the holds of every chunk`() =
        runTest {
            val budget = TokenBudget(maxTokens = PATHS.size * HOLD)

            assertFailsWith<CancellationException> {
                withContext(Dispatchers.Default) { dispatch(JitterProvider(cancelOn = "C.kt"), budget) }
            }

            // Only spends stay charged; one leaked hold alone would exceed every chunk's spend together.
            assertTrue(budget.totalConsumed() <= PATHS.size * SPEND, "consumed ${budget.totalConsumed()}")
        }

    @Test
    fun `a failing chunk propagates its error and returns the holds of every chunk`() =
        runTest {
            val budget = TokenBudget(maxTokens = PATHS.size * HOLD)

            assertFailsWith<IllegalStateException> {
                withContext(Dispatchers.Default) { dispatch(JitterProvider(failOn = "C.kt"), budget) }
            }

            assertTrue(budget.totalConsumed() <= PATHS.size * SPEND, "consumed ${budget.totalConsumed()}")
        }

    @Test
    fun `the same MR run twice gives byte identical ledger notes and prompts`() =
        runTest {
            val changes = UNIT_PATHS.map(::change)
            val runs = withContext(Dispatchers.Default) {
                List(RUNS) {
                    val provider = JitterProvider()
                    val coverage = checkNotNull(dispatch(provider, TokenBudget(maxTokens = 3 * HOLD), UNIT_PATHS).coverage)
                    val notes = LargeMrSummary.findings(
                        changes,
                        listOf(coverage),
                        AiLargeMrConfig(maxUnits = 2),
                        AiExcludePolicy.DEFAULT_PATTERNS
                    )
                    Triple(
                        KarandashJson.Default.encodeToString(RuleCoverage.serializer(), coverage),
                        notes.toString(),
                        provider.prompts.sorted()
                    )
                }
            }

            assertEquals(3, runs.first().third.size)
            runs.forEach { assertEquals(runs.first(), it) }
        }

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = DIFF)

    private companion object {
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
        val PATHS = listOf("A.kt", "B.kt", "C.kt", "D.kt", "E.kt", "F.kt")
        val UNIT_PATHS = listOf(
            "app/src/main/A.kt",
            "app/src/test/ATest.kt",
            "net/src/main/B.kt",
            "db/src/main/C.kt",
            "ui/src/main/D.kt",
            "core/src/main/E.kt",
        )
        const val HOLD = CustomAiRuleExecutor.ESTIMATED_TOKENS
        const val SPEND = 7
        const val PERMITS = 8
        const val PARALLEL = 4
        const val RUNS = 20
        const val RULE_ID = "my-rule"
        val CUSTOM_RULES = listOf(CustomAiRule(id = RULE_ID, name = "Mine", prompt = "Check it"))
        const val MAX_JITTER_MS = 20L

        /** A 1-token reply reserve, so a chunk's hold is the rule's estimate (0 means the 2000 default). */
        val TINY_RESERVE = AiBudgetConfig(reserveOutputTokens = 1)
    }
}
