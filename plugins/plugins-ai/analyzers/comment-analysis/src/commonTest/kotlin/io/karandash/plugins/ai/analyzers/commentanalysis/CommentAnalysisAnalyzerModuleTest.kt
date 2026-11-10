@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.analyzers.commentanalysis

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommentAnalysisAnalyzerModuleTest {
    private val module = CommentAnalysisAnalyzerModule()

    private fun context(
        service: FakeAiService,
        mr: MergeRequest = createTestMergeRequest(title = "Add cache", description = "No behavior change."),
    ) = AiAnalyzerContext(mr = mr, aiService = service, budget = TokenBudget(50_000), model = "m")

    @Test
    fun `threads and description are judged once per MR not per diff chunk`() {
        assertTrue(module.mrLevel)
    }

    @Test
    fun `issues map to findings with category and severity`() =
        runTest {
            val service = FakeAiService(
                """{"issues":[{"message":"Claims no behavior change","category":"accuracy","severity":"warning","confidence":0.8}]}""",
            )
            val finding = module.analyzeWithThreads(context(service)).single()

            assertEquals(Severity.WARNING, finding.severity)
            assertEquals("Claims no behavior change", finding.message)
            assertEquals("accuracy", finding.metadata[AiFindingMetadataKeys.CATEGORY])
            assertNull(finding.location)
            assertTrue(
                service.requests
                    .single()
                    .prompt
                    .contains("No behavior change.")
            )
        }

    @Test
    fun `an MR without open review threads skips the AI call even with a description`() =
        runTest {
            val service = FakeAiService("{}")
            val noService = module.analyze(context(service))
            val noThreads = module.analyzeWithThreads(context(service), threads = emptyList())

            assertTrue(noService.isEmpty() && noThreads.isEmpty())
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `threads the bot started do not count as a discussion`() =
        runTest {
            val service = FakeAiService("""{"issues":[]}""")
            val bot = ReviewThread("b1", listOf(ReviewNote("bn1", "Null check missing", author = "karandash-bot")))
            val marked = ReviewThread("b2", listOf(ReviewNote("bn2", "${InlineFindingMarker.PREFIX}x -->\nUnused import")))
            val botOnly = module.analyzeWithThreads(context(service), threads = listOf(bot, marked), bot = "karandash-bot")

            assertTrue(botOnly.isEmpty())
            assertTrue(service.requests.isEmpty())

            module.analyzeWithThreads(context(service), threads = listOf(bot, marked, HUMAN_THREAD), bot = "karandash-bot")
            val prompt = service.requests.single().prompt
            assertTrue("**Open review threads (1):**" in prompt, prompt)
            assertFalse("Null check missing" in prompt || "Unused import" in prompt, prompt)
        }

    @Test
    fun `empty issue list yields no finding`() =
        runTest {
            assertTrue(module.analyzeWithThreads(context(FakeAiService("""{"issues":[]}"""))).isEmpty())
        }

    @Test
    fun `malformed response yields a parse error finding`() =
        runTest {
            val finding = module.analyzeWithThreads(context(FakeAiService("I could not parse the request"))).single()

            assertEquals("parse_error", finding.metadata["error"])
            assertEquals(CommentAnalysisAnalyzerModule.ANALYZER_ID, finding.rule)
        }

    /** the open review threads reach the prompt; resolved threads and system notes stay out. */
    @Test
    fun `review-thread-analysis reads the open review threads`() =
        runTest {
            val service = FakeAiService("""{"issues":[]}""")
            val threads = listOf(
                ReviewThread("t1", listOf(ReviewNote("n1", "Why is the cache unbounded", author = "ann"), ReviewNote("n2", "ok"))),
                ReviewThread("t2", listOf(ReviewNote("n3", "Done earlier", author = "bob")), resolved = true),
                ReviewThread("t3", listOf(ReviewNote("n4", "changed this line", system = true))),
            )
            module.analyzeWithThreads(context(service, createTestMergeRequest(description = " ")), threads)

            val prompt = service.requests.single().prompt
            assertTrue("**Open review threads (1):**" in prompt, prompt)
            assertTrue("- [ann] Why is the cache unbounded (1 replies)" in prompt, prompt)
            assertFalse("Done earlier" in prompt || "changed this line" in prompt, prompt)
            assertEquals("review-thread-analysis", module.analyzerId)
        }

    /** thread notes come from the platform unredacted; a secret in one never reaches the provider. */
    @Test
    fun `a secret in a review thread note never reaches the provider`() =
        runTest {
            val token = "ghp_" + "A".repeat(36)
            val service = FakeAiService("""{"issues":[]}""")
            val threads = listOf(ReviewThread("t1", listOf(ReviewNote("n1", "use $token"))))
            module.analyzeWithThreads(context(service, createTestMergeRequest(description = " ")), threads)

            assertFalse(token in service.requests.single().prompt, service.requests.single().prompt)
        }

    @Test
    fun `a long MR description reaches the prompt whole`() {
        val description = (1..1_000).joinToString(" ") { "word$it" }
        val prompt = module.buildPrompt(createTestMergeRequest(title = "Add cache", description = description))
        assertTrue(description in prompt, "description cut in the prompt")
        assertTrue("(truncated)" !in prompt)
    }

    @Test
    fun `extra instructions reach the system prompt and the base prompt survives`() =
        runTest {
            val service = FakeAiService("""{"issues":[]}""")
            module.analyzeWithThreads(context(service).copy(extraInstructions = "Project rule: never log card numbers"))

            val system = service.requests
                .single()
                .systemPrompt
                .orEmpty()
            assertTrue(system.contains("Project rule: never log card numbers"), system)
            assertTrue(system.contains("Required JSON format"), system)
        }
}
