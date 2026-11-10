package io.karandash.plugins.ai.dispatch

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.plugins.ai.rules.CustomAiRule
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The chunks of one rule are sent in parallel, up to `ai.large-mr.max-parallel` and the shared semaphore. */
class AiRuleDispatcherParallelChunksTest {
    private class CountingProvider : AiProvider {
        override val providerId = "fake"
        var active = 0
        var peak = 0
        var calls = 0

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse {
            calls++
            active++
            peak = maxOf(peak, active)
            delay(CALL_MS)
            active--
            return AiResponse("{}", 0, 0, 0, model)
        }

        override suspend fun close() = Unit
    }

    private suspend fun dispatch(
        provider: AiProvider,
        semaphore: Semaphore,
        maxParallel: Int,
    ) = AiRuleDispatcher(buildTestRegistry(), NoOpLogger).dispatch(
        AiRuleDispatcher.DispatchInput(
            ruleId = RULE_ID,
            mr = createTestMergeRequest(changes = listOf("A.kt", "B.kt", "C.kt", "D.kt").map(::change)),
            concurrencySemaphore = semaphore,
            aiProvider = provider,
            budget = TokenBudget(maxTokens = 1_000_000),
            model = "m",
            config = JsonObject(emptyMap()),
            resolvedPrompt = null,
            aiService = null,
            // Each file's diff exceeds the per-chunk size, so the MR splits into one chunk per file.
            aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = DIFF.length),
            pluginCfg = AiPluginConfig(largeMr = AiLargeMrConfig(maxParallel = maxParallel)),
            contextProcessors = emptyList(),
            accumulatedFindings = emptyList(),
            customRules = listOf(CustomAiRule(id = RULE_ID, name = "Mine", prompt = "Check it")),
        ),
    )

    @Test
    fun `chunks of one rule run in parallel up to max-parallel`() =
        runTest {
            val provider = CountingProvider()

            val result = dispatch(provider, Semaphore(PERMITS), maxParallel = 2)

            assertEquals(4, provider.calls)
            assertEquals(2, provider.peak)
            assertEquals(4, result.coverage?.reviewed?.size)
            assertEquals(0, result.coverage?.chunksSkipped)
        }

    @Test
    fun `parallel chunks still take a permit of the shared semaphore`() =
        runTest {
            val provider = CountingProvider()

            dispatch(provider, Semaphore(1), maxParallel = 4)

            assertEquals(4, provider.calls)
            assertEquals(1, provider.peak)
        }

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = DIFF)

    private companion object {
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
        const val PERMITS = 8
        const val RULE_ID = "my-rule"
        const val CALL_MS = 100L
    }
}
