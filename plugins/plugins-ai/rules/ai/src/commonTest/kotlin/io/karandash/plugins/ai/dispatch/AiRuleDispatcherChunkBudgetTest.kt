package io.karandash.plugins.ai.dispatch

import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
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

class AiRuleDispatcherChunkBudgetTest {
    @Test
    fun `an exhausted token budget skips the remaining chunks with a warning that counts them`() =
        runTest {
            val logger = WarnLogger()
            val calls = mutableListOf<String>()
            val provider = object : AiProvider {
                override val providerId = "fake"

                override suspend fun complete(
                    prompt: String,
                    systemPrompt: String?,
                    model: String,
                    maxTokens: Int,
                    temperature: Double,
                    jsonMode: Boolean,
                ): AiResponse {
                    calls += prompt
                    return AiResponse("{}", 0, 0, 0, model)
                }

                override suspend fun close() = Unit
            }
            val mr = createTestMergeRequest(changes = listOf(change("A.kt"), change("B.kt")))

            val result = AiRuleDispatcher(buildTestRegistry(), logger).dispatch(
                AiRuleDispatcher.DispatchInput(
                    ruleId = "review-comments",
                    mr = mr,
                    concurrencySemaphore = Semaphore(1),
                    aiProvider = provider,
                    budget = TokenBudget(maxTokens = 0),
                    model = "m",
                    config = JsonObject(emptyMap()),
                    resolvedPrompt = null,
                    aiService = null,
                    // Each file's diff exceeds the per-chunk size, so the MR splits into two chunks.
                    aiRuleConfig = AiRuleConfig(maxDiffCharsTotal = DIFF.length),
                    pluginCfg = AiPluginConfig(),
                    contextProcessors = emptyList(),
                    accumulatedFindings = emptyList(),
                    customRules = emptyList(),
                ),
            )

            assertEquals(emptyList(), result.findings)
            assertEquals(emptyList(), calls)
            assertEquals(
                listOf("[ai] rule=review-comments: token budget exhausted, skipped 2 of 2 chunks (2 files not reviewed: A.kt, B.kt)"),
                logger.warnings,
            )
            assertEquals(
                listOf(0 to listOf("A.kt"), 1 to listOf("B.kt")),
                result.skippedChunks.map { it.index to it.files },
            )
            assertEquals(setOf("token budget exhausted"), result.skippedChunks.map { it.reason }.toSet())
            assertEquals(0, result.diff?.filesOut)
        }

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = DIFF)

    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private companion object {
        const val DIFF = "@@ -1,1 +1,1 @@\n-old line\n+new line"
    }
}
