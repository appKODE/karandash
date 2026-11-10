package io.karandash.contracts.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The head file content ([io.karandash.models.FileChange.newContent]) reaches the review request through
 * `ai.reviewMode: full` and the full-file context block, and the block stays within its context budget with
 * every cut named in the coverage ledger.
 */
class AiFullFileContextContractTest {
    private val headFile = (1..HEAD_LINES).joinToString("\n") { "val line$it = \"$HEAD_MARKER $it\"" }

    private suspend fun review(
        aiBlockYaml: String,
        newContent: String? = headFile,
    ): AiReviewHarness {
        val harness = AiReviewHarness()
        val change = AiReviewHarness.aKtChange().copy(newContent = newContent)
        harness.run(aiBlockYaml = aiBlockYaml, mr = AiReviewHarness.defaultMr(listOf(change)))
        assertTrue(harness.requestBodies.isNotEmpty(), "setup: no LLM request reached the MockEngine")
        return harness
    }

    @Test
    fun `review mode full sends the head file content`() =
        runTest(timeout = 2.minutes) {
            val harness = review("  reviewMode: full")

            assertTrue(harness.allRequests.contains("$HEAD_MARKER $HEAD_LINES"), "head file content missing in full mode")
        }

    @Test
    fun `the full file block is present when the head content is known`() =
        runTest(timeout = 2.minutes) {
            val harness = review(FULL_FILE_ON)

            assertTrue(harness.allRequests.contains(BLOCK_HEADING), "full-file block missing")
            assertTrue(harness.allRequests.contains("$HEAD_MARKER $HEAD_LINES"), "full-file block lacks the head content")
        }

    @Test
    fun `no full file block without head content`() =
        runTest(timeout = 2.minutes) {
            val harness = review(FULL_FILE_ON, newContent = null)

            assertFalse(harness.allRequests.contains(BLOCK_HEADING), "full-file block sent without head content")
        }

    @Test
    fun `a full file over its context budget is left out and named as a cut`() =
        runTest(timeout = 2.minutes) {
            val harness = review(FULL_FILE_ON + "\n" + budgetYaml(SMALL_BUDGET))

            assertFalse(harness.allRequests.contains("$HEAD_MARKER $HEAD_LINES"), "full file sent past its context budget")
            assertTrue(
                // a trimmed context block is routine, so the ledger line is INFO (WARN only when diff files are left out)
                harness.logger.messages.any { "left out to fit the budget" in it && "full-file-context (cut to $SMALL_BUDGET of" in it },
                "cut not named in the ledger: ${harness.logger.messages}",
            )
        }

    @Test
    fun `a full file within its context budget is sent whole`() =
        runTest(timeout = 2.minutes) {
            val harness = review(FULL_FILE_ON + "\n" + budgetYaml(LARGE_BUDGET))

            assertTrue(harness.allRequests.contains("$HEAD_MARKER $HEAD_LINES"), "full file within budget was cut")
            assertFalse(harness.logger.messages.any { "full-file-context (cut" in it }, "uncut block named as a cut")
        }

    private fun budgetYaml(maxChars: Int): String =
        """
        |    knowledge-context:
        |      budgets:
        |        "full-file-context": { max-chars: $maxChars }
        """.trimMargin()

    private companion object {
        const val HEAD_MARKER = "HEAD-CONTENT"
        const val HEAD_LINES = 40
        const val BLOCK_HEADING = "#### Full file context"
        const val SMALL_BUDGET = 200
        const val LARGE_BUDGET = 20_000
        val FULL_FILE_ON =
            """
            |  contextQuality:
            |    fullFileContext:
            |      enabled: true
            """.trimMargin()
    }
}
