package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.PromptTokenCalibration
import io.karandash.plugins.ai.core.ReasoningEffortOverride
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallWatchTest {
    private class Replying(
        private val promptTokens: (AiCompletionRequest) -> Int,
        private val completionTokens: Int,
    ) : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = AiCompletionResponse("{}", promptTokens(request), completionTokens, 0, request.model)
    }

    private val request = AiCompletionRequest(prompt = "p".repeat(400), systemPrompt = "s".repeat(400), model = "m", maxTokens = 100)
    private val estimate = TokenUnit.tokensOf(request.prompt) + TokenUnit.tokensOf(request.systemPrompt.orEmpty())

    @Test
    fun `records actual over estimated prompt tokens per model`() =
        runTest {
            val calibration = PromptTokenCalibration()
            val watch = CallWatch(Replying({ estimate * 2 }, completionTokens = 10), calibration)

            watch.complete(request, null)

            assertEquals(2.0, calibration.ratio("m"))
            assertEquals(1.0, calibration.ratio("other"))
            assertEquals(500, calibration.adjust("m", 1_000))
        }

    @Test
    fun `a reply that fills its output cap marks the call truncated`() =
        runTest {
            val watch = CallWatch(Replying({ 0 }, completionTokens = request.maxTokens), PromptTokenCalibration())

            watch.complete(request, null)

            assertTrue(watch.truncated)
        }

    @Test
    fun `a reply under its output cap or without usage is not truncated`() =
        runTest {
            val under = CallWatch(Replying({ 0 }, completionTokens = request.maxTokens - 1), PromptTokenCalibration())
            val noUsage = CallWatch(Replying({ 0 }, completionTokens = 0), PromptTokenCalibration())

            under.complete(request, null)
            noUsage.complete(request, null)

            assertFalse(under.truncated)
            assertFalse(noUsage.truncated)
        }

    /** A reasoning model that thinks until its cap unless reasoning is off; [failRetry] rejects the `reasoning` field. */
    private class Thinking(
        private val failRetry: Boolean = false,
    ) : AiService {
        override val isAvailable = true
        val efforts = mutableListOf<String?>()

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            val effort = ReasoningEffortOverride.current()
            efforts += effort
            if (effort == null) return AiCompletionResponse("", 10, request.maxTokens, 10 + request.maxTokens, request.model)
            if (failRetry) throw AiProviderException("reasoning not supported", statusCode = 400)
            return AiCompletionResponse("{}", 10, 20, 30, request.model)
        }
    }

    @Test
    fun `an empty reply at its output cap is retried once with reasoning off`() =
        runTest {
            val thinking = Thinking()
            val watch = CallWatch(thinking, PromptTokenCalibration())

            val response = watch.complete(request, null)

            assertEquals(listOf(null, ReasoningEffortOverride.NONE), thinking.efforts)
            assertEquals("{}", response.content)
            assertEquals(30 + 10 + request.maxTokens, response.totalTokens)
            assertFalse(watch.truncated)
        }

    @Test
    fun `no retry when ai reasoningEffort is already none`() =
        runTest {
            val thinking = Thinking()
            val watch = CallWatch(thinking, PromptTokenCalibration(), configuredEffort = ReasoningEffortOverride.NONE)

            assertEquals("", watch.complete(request, null).content)
            assertEquals(listOf<String?>(null), thinking.efforts)
            assertTrue(watch.truncated)
        }

    @Test
    fun `a failed retry keeps the empty reply as truncated`() =
        runTest {
            val watch = CallWatch(Thinking(failRetry = true), PromptTokenCalibration())

            assertEquals("", watch.complete(request, null).content)
            assertTrue(watch.truncated)
        }

    @Test
    fun `concurrent records all reach the totals`() =
        runTest {
            val calibration = PromptTokenCalibration()
            withContext(Dispatchers.Default) {
                coroutineScope { repeat(CONCURRENT) { launch { calibration.record("m", 3, 2) } } }
            }

            assertEquals(1.5, calibration.ratio("m"))
        }

    @Test
    fun `retryHalves runs each half once and keeps the rule paths of the whole diff`() =
        runTest {
            val seen = mutableListOf<List<String>>()
            val input = input(listOf(change("A.kt"), change("B.kt")))

            val findings = retryHalves(input, NoOpLogger) { half ->
                seen += half.mr.changes.map { it.path }
                assertEquals(setOf("A.kt", "B.kt"), half.rulePaths)
                listOf(
                    createTestFinding(
                        message = half.mr.changes
                            .first()
                            .path
                    )
                )
            }

            assertEquals(listOf(listOf("A.kt"), listOf("B.kt")), seen)
            assertEquals(listOf("A.kt", "B.kt"), findings?.map(Finding::message))
        }

    @Test
    fun `retryHalves gives up on a diff of one hunk`() =
        runTest {
            val findings = retryHalves(input(listOf(change("A.kt"))), NoOpLogger) { error("no half to run") }

            assertNull(findings)
        }

    @Test
    fun `the halves replace the parse error of the capped reply`() {
        val parseError = AiRuleAnalyzerUtils.parseErrorFinding("review-comments", "{\"findings\": [", "m", 0)
        val kept = createTestFinding(message = "real issue")
        val half = createTestFinding(message = "from a half")

        val findings = replaceCapped(parseError + kept, listOf(half))

        assertEquals(listOf("real issue", "from a half"), findings.map(Finding::message))
    }

    private fun change(path: String) =
        FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 1, diffContent = "@@ -1,1 +1,1 @@\n-old\n+new")

    private fun input(changes: List<FileChange>) =
        AiRuleDispatcher.DispatchInput(
            ruleId = "review-comments",
            mr = createTestMergeRequest(changes = changes),
            concurrencySemaphore = Semaphore(1),
            aiProvider = UnusedProvider,
            budget = TokenBudget(maxTokens = 100_000),
            model = "m",
            config = JsonObject(emptyMap()),
            resolvedPrompt = null,
            aiService = null,
            aiRuleConfig = AiRuleConfig(),
            pluginCfg = AiPluginConfig(),
            contextProcessors = emptyList(),
            accumulatedFindings = emptyList(),
            customRules = emptyList(),
        )

    private object UnusedProvider : AiProvider {
        override val providerId = "unused"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse = error("not called")

        override suspend fun close() = Unit
    }

    private companion object {
        const val CONCURRENT = 200
    }
}
