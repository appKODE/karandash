package io.karandash.plugins.ai.analyzers.commentanalysis

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommentAnalysisBehaviorTest {
    private val module = CommentAnalysisAnalyzerModule()

    private fun commit(message: String) = Commit("sha", message, CommitAuthor("Dev", "d@t.com"), "2024-01-01T00:00:00Z")

    private class ThrowingAiService(
        private val error: Exception,
    ) : AiService {
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = throw error
    }

    private fun thread(
        id: String,
        author: String,
        body: String = "Is this tested",
        resolved: Boolean = false,
    ) = ReviewThread(id, listOf(ReviewNote("n$id", body, author = author)), resolved = resolved)

    private suspend fun requestsFor(
        threads: List<ReviewThread>,
        bot: String? = null,
        botFails: Boolean = false,
    ): Pair<Int, List<String>> {
        val service = FakeAiService("""{"issues":[]}""")
        val skips = mutableListOf<String>()
        val context = AiAnalyzerContext(
            mr = createTestMergeRequest(description = "Text"),
            aiService = service,
            budget = TokenBudget(50_000),
            model = "m",
            onSkip = { skips += it },
        )
        module.analyzeWithThreads(context, threads, bot, botFails)
        return service.requests.size to skips
    }

    @Test
    fun `no human thread means no model call and a named skip`() =
        runTest {
            val none = requestsFor(emptyList())
            val botOnly =
                requestsFor(
                    listOf(thread("b", "karandash-bot"), thread("m", "ann", InlineFindingMarker.PREFIX + "{} -->")),
                    bot = "karandash-bot"
                )
            val resolvedOnly = requestsFor(listOf(thread("r", "ann", resolved = true)))

            listOf(none, botOnly, resolvedOnly).forEach { (calls, skips) ->
                assertEquals(0, calls)
                assertEquals(listOf("no open review threads"), skips)
            }
        }

    @Test
    fun `a human thread is analyzed next to ignored bot threads and an unknown bot falls back to the marker`() =
        runTest {
            assertEquals(1, requestsFor(listOf(thread("b", "karandash-bot"), thread("h", "ann")), bot = "karandash-bot").first)
            assertEquals(1, requestsFor(listOf(thread("h", "ann")), botFails = true).first)
            assertEquals(0, requestsFor(listOf(thread("m", "x", InlineFindingMarker.PREFIX + "{} -->")), botFails = true).first)
        }

    @Test
    fun `commits alone are analyzed with only each first line and a capped list`() =
        runTest {
            val service = FakeAiService("""{"issues":[]}""")
            val commits = listOf(commit("fix\n\nlong body text")) + (2..20).map { commit("change $it") }
            val mr = createTestMergeRequest(description = null, commits = commits)

            module.analyzeWithThreads(AiAnalyzerContext(mr = mr, aiService = service, budget = TokenBudget(50_000), model = "m"))

            val prompt = service.requests.single().prompt
            assertTrue("**Commit messages (20):**" in prompt, prompt)
            assertTrue("- fix\n" in prompt)
            assertFalse("long body text" in prompt)
            assertTrue("- change 15\n" in prompt)
            assertFalse("- change 16\n" in prompt)
            assertTrue("- ... and 5 more" in prompt)
            assertFalse("**MR Description:**" in prompt)
        }

    @Test
    fun `limits descriptionMaxTokens caps the response and spend is left to the dispatcher`() =
        runTest {
            val service = FakeAiService(
                """{"issues":[{"message":"Open question","category":"unresolved_question","confidence":0.75}]}""",
                totalTokens = 222,
            )
            val budget = TokenBudget(1_000)
            val config = buildJsonObject { putJsonObject("limits") { put("descriptionMaxTokens", 64) } }
            val mr = createTestMergeRequest(description = "Should we cache this?")

            val finding = module
                .analyzeWithThreads(AiAnalyzerContext(mr = mr, aiService = service, budget = budget, model = "m", config = config))
                .single()

            assertEquals(64, service.requests.single().maxTokens)
            assertEquals(1_000, budget.remaining())
            assertEquals("0.75", finding.metadata[AiFindingMetadataKeys.CONFIDENCE])
            assertEquals("info", finding.severity.name.lowercase())
        }

    @Test
    fun `provider and other failures propagate to the pipeline boundary`() =
        runTest {
            val mr = createTestMergeRequest(description = "Text")

            fun ctx(service: AiService) = AiAnalyzerContext(mr = mr, aiService = service, budget = TokenBudget(50_000), model = "m")

            assertFailsWith<AiProviderException> { module.analyzeWithThreads(ctx(ThrowingAiService(AiProviderException("down")))) }
            assertFailsWith<IllegalStateException> { module.analyzeWithThreads(ctx(ThrowingAiService(IllegalStateException("bad")))) }
        }

    @Test
    fun `module metadata describes an opt-in analyzer`() {
        assertEquals("reviewThreadAnalysis", module.featureKey)
        assertFalse(module.defaultEnabled)
        assertTrue(module.estimatedTokens > 0)
        assertTrue("issues" in module.responseFormatSuffix)
        assertTrue(module.explanation.isNotBlank())
    }
}
