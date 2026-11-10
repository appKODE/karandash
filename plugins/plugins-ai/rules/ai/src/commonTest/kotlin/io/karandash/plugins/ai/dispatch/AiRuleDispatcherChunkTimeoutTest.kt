package io.karandash.plugins.ai.dispatch

import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.plugins.ai.rules.CustomAiRule
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** A chunk still running at the rule deadline is dropped; the chunks already done keep their findings. */
class AiRuleDispatcherChunkTimeoutTest {
    private val provider = object : AiProvider {
        override val providerId = "fake"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse {
            if (HANGING in prompt) awaitCancellation()
            val file = FILES.first { it in prompt }
            return AiResponse("""{"message": "issue in $file"}""", 0, 0, 0, model)
        }

        override suspend fun close() = Unit
    }

    @Test
    fun `a hanging chunk times out and the finished chunks are kept`() =
        runTest {
            val result = AiRuleDispatcher(buildTestRegistry(), NoOpLogger).dispatch(
                AiRuleDispatcher.DispatchInput(
                    ruleId = RULE_ID,
                    mr = createTestMergeRequest(changes = FILES.map(::change)),
                    concurrencySemaphore = Semaphore(FILES.size),
                    aiProvider = provider,
                    budget = TokenBudget(maxTokens = 1_000_000),
                    model = "m",
                    config = JsonObject(emptyMap()),
                    resolvedPrompt = null,
                    aiService = null,
                    // Each file's diff exceeds the per-chunk size, so the MR splits into one chunk per file.
                    aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = DIFF.length),
                    pluginCfg = AiPluginConfig(),
                    contextProcessors = emptyList(),
                    accumulatedFindings = emptyList(),
                    customRules = listOf(CustomAiRule(id = RULE_ID, name = "Mine", prompt = "Check it")),
                    deadline = TimeSource.Monotonic.markNow() + 1.minutes,
                ),
            )

            assertEquals(setOf("issue in A.kt", "issue in C.kt"), result.findings.map { it.message }.toSet())
            assertEquals(1, result.coverage?.chunksSkipped)
            assertEquals(setOf("A.kt", "C.kt"), result.coverage?.reviewed)
            assertEquals(listOf(DiffCuts.TIMED_OUT), result.skippedChunks.map { it.reason })
            assertEquals(listOf(HANGING), result.skippedChunks.flatMap { it.files })
        }

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = DIFF)

    private companion object {
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
        const val RULE_ID = "my-rule"
        const val HANGING = "B.kt"
        val FILES = listOf("A.kt", HANGING, "C.kt")
    }
}
