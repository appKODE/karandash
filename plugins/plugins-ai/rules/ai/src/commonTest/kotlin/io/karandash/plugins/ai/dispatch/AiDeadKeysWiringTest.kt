package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.logger.Logger
import io.karandash.plugins.ai.core.RuleGate
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.config.CostBudgetConfig
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `costBudget.maxCostPerRunUsd` and the enrichment `model`/`maxTokens` take effect. */
class AiDeadKeysWiringTest {
    private val quiet = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private val evaluator = AiSkipEvaluator(logger = quiet, ruleGate = RuleGate())
    private val mr = createTestMergeRequest()

    @Test
    fun `maxCostPerRunUsd skips the rule once the spent USD reaches the cap`() {
        val config = CostBudgetConfig(maxTokensPerRun = 1_000_000, maxCostPerRunUsd = 1.0)

        val result = evaluator.evaluateCostBudget("r", mr, config, TokenBudget(1_000_000), spentUsd = 1.5)

        val skip = assertIs<SkipResult.Skip>(result)
        assertTrue("max-cost-per-run-usd" in skip.reason, skip.reason)
    }

    @Test
    fun `maxCostPerRunUsd below the cap keeps the rule running`() {
        val config = CostBudgetConfig(maxTokensPerRun = 1_000_000, maxCostPerRunUsd = 1.0)

        assertEquals(SkipResult.Continue, evaluator.evaluateCostBudget("r", mr, config, TokenBudget(1_000_000), spentUsd = 0.5))
    }

    @Test
    fun `enrichment model and maxTokens override every call of the run`() =
        runTest {
            val delegate = FakeAiService("ok")
            val override = RuleCallOverride.fromConfig(
                JsonObject(mapOf("model" to JsonPrimitive("enrich-m"), "maxTokens" to JsonPrimitive(321))),
            )

            RuleCallOverride.Service(delegate, override).complete(AiCompletionRequest(prompt = "p", model = "base", maxTokens = 4096), null)

            val sent = delegate.requests.single()
            assertEquals("enrich-m", sent.model)
            assertEquals(321, sent.maxTokens)
        }

    @Test
    fun `a config without model and maxTokens is an empty override`() {
        assertTrue(RuleCallOverride.fromConfig(JsonObject(emptyMap())).isEmpty)
    }

    @Test
    fun `maxSystemPromptChars caps the system prompt of every call`() =
        runTest {
            val delegate = FakeAiService("ok")
            val override = RuleCallOverride(systemPromptChars = 10)

            RuleCallOverride
                .Service(delegate, override)
                .complete(AiCompletionRequest(prompt = "p", systemPrompt = "x".repeat(50), model = "m"), null)
            RuleCallOverride
                .Service(delegate, override)
                .complete(AiCompletionRequest(prompt = "p", systemPrompt = "short", model = "m"), null)

            assertEquals(listOf("x".repeat(10), "short"), delegate.requests.map { it.systemPrompt })
        }
}
