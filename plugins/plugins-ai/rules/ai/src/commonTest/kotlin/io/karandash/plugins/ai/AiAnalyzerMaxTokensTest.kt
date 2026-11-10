package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiTokenAllocatorModule
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.RuleGate
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.config.CostBudgetConfig
import io.karandash.plugins.ai.dispatch.AiSkipEvaluator
import io.karandash.plugins.ai.dispatch.SkipResult
import io.karandash.plugins.ai.init.AiRuleInitializer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `ai.<analyzer>.max-tokens` sets that analyzer's token budget; `cost-budget.max-tokens-per-run` stays the safety net. */
class AiAnalyzerMaxTokensTest {
    private val warnings = mutableListOf<String>()

    private suspend fun budgets(
        aiBlock: Map<String, Any>,
        registry: ModuleRegistry = buildTestRegistry(),
    ): Map<String, Int> {
        val context = object : PluginContext {
            override val pluginConfig: JsonElement =
                AiPluginConfig.topLevelAiBlockToPluginConfigElement(mapToJsonElement(PROVIDER + aiBlock))
            override val enrichment: EnrichmentContext = EnrichmentContext.EMPTY

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {
                warnings += message
            }

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }
        val result = checkNotNull(AiRuleInitializer(registry, null).initialize(context)) { "AI module did not start" }
        return result.perRuleBudgets.mapValues { it.value.remaining() } + (RUN to checkNotNull(result.tokenBudget).remaining())
    }

    @Test
    fun `a base 1-41-0 config without per-analyzer values keeps every analyzer with review-comments on half the budget`() =
        runTest {
            val budgets = budgets(
                mapOf(
                    "maxTokenBudget" to 30_000,
                    "reviewComments" to mapOf("enabled" to true, "temperature" to 0.2),
                    "riskScoring" to mapOf("enabled" to true),
                    "descriptionQuality" to mapOf("enabled" to true),
                ),
            )

            val expected = mapOf("risk-scoring" to 7500, "review-comments" to 15_000, "description-quality" to 7500, RUN to 30_000)
            assertEquals(expected, budgets)
            assertTrue(warnings.none { "max-tokens" in it || "unsupported" in it }, warnings.toString())
        }

    @Test
    fun `an explicit max-tokens wins and the other analyzers get their estimate`() =
        runTest {
            val budgets = budgets(mapOf("maxTokenBudget" to 30_000, "reviewComments" to mapOf("maxTokens" to 12_000)))

            assertEquals(mapOf("risk-scoring" to 1500, "review-comments" to 12_000, RUN to 30_000), budgets)
            assertTrue(warnings.none { "unsupported" in it }, warnings.toString())
        }

    @Test
    fun `per-analyzer values over a set max-token-budget keep the ceiling and warn naming the analyzers cut`() =
        runTest {
            val budgets = budgets(
                mapOf(
                    "maxTokenBudget" to 30_000,
                    "reviewComments" to mapOf("max-tokens" to 40_000),
                    "largeMr" to mapOf("scaleBudget" to false),
                ),
            )

            assertEquals(40_000, budgets["review-comments"])
            assertEquals(30_000, budgets[RUN], "the per-analyzer sum raised the max-token-budget ceiling")
            val warning = warnings.single { "over max-token-budget" in it }
            assertTrue("41500" in warning && "30000" in warning && "review-comments" in warning, warning)
            assertTrue("risk-scoring" !in warning.substringAfter("cut"), warning)
        }

    @Test
    fun `under scale-budget per-analyzer values over max-token-budget do not warn`() =
        runTest {
            budgets(mapOf("maxTokenBudget" to 30_000, "reviewComments" to mapOf("max-tokens" to 40_000)))

            assertTrue(warnings.none { "over max-token-budget" in it }, warnings.toString())
        }

    @Test
    fun `without max-token-budget the run budget covers the per-analyzer sum`() =
        runTest {
            val budgets = budgets(mapOf("reviewComments" to mapOf("maxTokens" to 60_000)))

            assertEquals(61_500, budgets[RUN])
            assertTrue(warnings.none { "over max-token-budget" in it }, warnings.toString())
        }

    @Test
    fun `explicit max-tokens go through the token allocator which splits what is left`() =
        runTest {
            val calls = mutableListOf<Pair<Set<String>, Int>>()
            val allocator = object : AiTokenAllocatorModule {
                override val id = "test-allocator"
                override val name = "test-allocator"
                override val version = "test"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun allocate(
                    rules: Set<String>,
                    totalBudget: Int,
                    priorities: Map<String, Int>,
                ): Map<String, Int> {
                    calls += rules to totalBudget
                    return rules.associateWith { totalBudget / rules.size }
                }
            }
            val registry = object : ModuleRegistry by buildTestRegistry() {
                override fun aiTokenAllocator(): AiTokenAllocatorModule = allocator
            }

            val budgets = budgets(mapOf("maxTokenBudget" to 30_000, "reviewComments" to mapOf("maxTokens" to 12_000)), registry)

            assertEquals(listOf(setOf("risk-scoring") to 18_000), calls)
            assertEquals(mapOf("risk-scoring" to 18_000, "review-comments" to 12_000, RUN to 30_000), budgets)
        }

    @Test
    fun `per-analyzer values over max-tokens-per-run warn naming the analyzers that may be cut`() =
        runTest {
            budgets(
                mapOf(
                    "riskScoring" to mapOf("enabled" to false),
                    "reviewComments" to mapOf("maxTokens" to 6000),
                    "costBudget" to mapOf("maxTokensPerRun" to 5000),
                ),
            )

            val warning = warnings.single { "max-tokens-per-run" in it }
            assertTrue("6000" in warning && "5000" in warning && "review-comments" in warning, warning)
        }

    @Test
    fun `a rule skipped at the run cap names the cap and shows skipped budget`() {
        val logged = mutableListOf<String>()
        val logger = object : Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) {
                logged += message
            }

            override fun warn(message: String) {
                logged += message
            }

            override fun error(message: String) = Unit
        }
        val spent = TokenBudget(10_000).also { it.recordSpend(5200) }

        val result = AiSkipEvaluator(logger = logger, ruleGate = RuleGate())
            .evaluateCostBudget("review-comments", MR, CostBudgetConfig(maxTokensPerRun = 5000), spent)

        val reason = assertIs<SkipResult.Skip>(result).reason
        assertEquals("skipped: budget — cost-budget.max-tokens-per-run cap of 5000 tokens reached (5200 used)", reason)
        assertTrue(logged.any { "review-comments" in it && reason in it }, logged.toString())
    }

    private companion object {
        const val RUN = "<run>"
        val PROVIDER = mapOf("provider" to "custom", "baseUrl" to "http://localhost:1234/v1", "model" to "test-model")
        val MR = MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = "d",
            sourceBranch = "feature/x",
            targetBranch = "main",
            author = User("u1", "dev", "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        )
    }
}
