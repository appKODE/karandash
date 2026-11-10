package io.karandash.plugins.ai.init

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The revived producers get their own budget: a base@1.41.0-shaped config
 * (`max-token-budget: 30000`, no per-analyzer `max-tokens`) with code-suggestions on keeps every other
 * analyzer's share unchanged.
 */
class AiSeparateProducerBudgetTest {
    private val warnings = mutableListOf<String>()

    private fun base1410(codeSuggestions: Boolean): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = AiPluginConfig.topLevelAiBlockToPluginConfigElement(
                KarandashJson.Default.parseToJsonElement(
                    """{"provider":"custom","baseUrl":"http://localhost:1234/v1","model":"test-model",
                   "statistics":{"dir":"build/no-such-stats-dir"},"maxTokenBudget":30000,"mode":"supplement",
                   "riskScoring":{"enabled":true},"reviewComments":{"enabled":true},
                   "codeSuggestions":{"enabled":$codeSuggestions,"mode":"inline","minSeverity":"warning"},
                   "reviewThreadAnalysis":{"enabled":true},"mrSummary":{"enabled":true},
                   "costBudget":{"maxTokensPerRun":30000,"onBudgetExceeded":"skip-remaining"}}""",
                ),
            )
            override val enrichment: EnrichmentContext = EnrichmentContext.EMPTY

            override fun env(name: String): String? = null

            override fun log(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private suspend fun init(codeSuggestions: Boolean) =
        assertNotNull(AiRuleInitializer(buildTestRegistry(), null).initialize(base1410(codeSuggestions)))

    private suspend fun shares(codeSuggestions: Boolean): Map<String, Int> =
        init(codeSuggestions).perRuleBudgets.mapValues { (_, budget) -> budget.remaining() }

    @Test
    fun `review-comments takes half of the split and the other analyzers share the rest`() =
        runTest {
            val with = shares(codeSuggestions = true)

            assertEquals(SHARED_BUDGETS, with - "code-suggestions", with.toString())
        }

    @Test
    fun `an explicit max-token-budget stays the run ceiling with code-suggestions on`() =
        runTest {
            assertEquals(30_000, init(codeSuggestions = true).tokenBudget?.remaining())
        }

    @Test
    fun `base 1_41_0 with code-suggestions on keeps the other analyzers budgets`() =
        runTest {
            val without = shares(codeSuggestions = false)
            val with = shares(codeSuggestions = true)

            assertEquals(without, with - "code-suggestions")
            assertEquals(CODE_SUGGESTIONS_ESTIMATE, with["code-suggestions"], with.toString())
        }

    @Test
    fun `the own budgets over the per-run cap are named at load`() =
        runTest {
            shares(codeSuggestions = true)

            assertTrue(warnings.any { "cost-budget.max-tokens-per-run" in it && "code-suggestions" in it }, warnings.toString())
        }

    private companion object {
        // The stub estimate in TestRegistryFactory.
        const val CODE_SUGGESTIONS_ESTIMATE = 3000

        // Per-analyzer budgets of this fixture: review-comments, the primary analyzer, takes half of the 30000 split.
        val SHARED_BUDGETS = mapOf(
            "risk-scoring" to 5000,
            "review-comments" to 15000,
            "review-thread-analysis" to 5000,
            "mr-summary" to 5000,
        )
    }
}
