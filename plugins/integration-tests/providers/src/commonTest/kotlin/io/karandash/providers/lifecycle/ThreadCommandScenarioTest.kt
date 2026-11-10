package io.karandash.providers.lifecycle

import io.karandash.api.run.MergeRequestThreads
import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ThreadFeedback
import io.karandash.providers.lifecycle.LifecycleHarness.Companion.finding
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reply ingestion and `@karandash` thread commands over several pushes: each push re-derives the feedback state from
 * the MR's threads (a fresh [FeedbackStore]), filters the findings through it, then reconciles inline comments.
 */
class ThreadCommandScenarioTest {
    private val code = "fun main() {\n    val x = compute()\n    println(x!!)\n}\n"
    private val npe = finding("null-safety", "a.kt", 3, "println(x!!)", "Avoid `!!` on x")

    /** One push with feedback: ingest the threads, drop rejected findings, post. Returns the findings kept. */
    private suspend fun LifecycleHarness.feedbackPush(
        sha: String,
        findings: List<Finding> = listOf(npe),
    ): List<Finding> {
        val store = FeedbackStore()
        ThreadFeedback.ingest(store, MergeRequestThreads.of(service(), "42", "7"), NoOpLogger)
        val kept = store.filterFindings(findings).kept
        push(sha, mapOf("a.kt" to code), kept)
        return kept
    }

    @Test
    fun `ignore command in reply gives zero reposts over three pushes`() =
        runTest {
            val h = LifecycleHarness()
            h.feedbackPush("sha1")
            h.gitlab.reply(
                h.gitlab.discussions
                    .single()
                    .id,
                "@karandash ignore"
            )
            val kept = listOf(h.feedbackPush("sha2"), h.feedbackPush("sha3"), h.feedbackPush("sha4"))
            assertEquals(1, h.gitlab.posts.size, "posts")
            assertEquals(1, h.gitlab.resolves.size, "resolves")
            assertTrue(kept.all { it.isEmpty() }, kept.toString())
        }

    @Test
    fun `an ignore command from a non-member is ignored`() =
        runTest {
            val h = LifecycleHarness()
            h.feedbackPush("sha1")
            h.gitlab.reply(
                h.gitlab.discussions
                    .single()
                    .id,
                "@karandash ignore",
                author = "mallory"
            )
            val kept = h.feedbackPush("sha2")
            assertEquals(listOf(npe), kept)
            assertEquals(1, h.gitlab.posts.size)
            assertEquals(0, h.gitlab.resolves.size)
        }

    @Test
    fun `a command outranks the keyword classifier`() =
        runTest {
            val h = LifecycleHarness()
            h.feedbackPush("sha1")
            h.gitlab.reply(
                h.gitlab.discussions
                    .single()
                    .id,
                "@karandash wontfix thanks anyway"
            )
            assertEquals(emptyList(), h.feedbackPush("sha2"))
            assertEquals(1, h.gitlab.resolves.size)
        }

    @Test
    fun `a human rebuttal is never re-raised`() =
        runTest {
            val h = LifecycleHarness()
            h.feedbackPush("sha1")
            h.gitlab.reply(
                h.gitlab.discussions
                    .single()
                    .id,
                "False positive, x is never null here"
            )
            val kept = listOf(h.feedbackPush("sha2"), h.feedbackPush("sha3"))
            assertTrue(kept.all { it.isEmpty() }, kept.toString())
            assertEquals(1, h.gitlab.posts.size)
            assertEquals(0, h.gitlab.replies.size, "the bot does not answer a rebuttal")
        }

    @Test
    fun `an agreeing reply keeps the finding`() =
        runTest {
            val h = LifecycleHarness()
            h.feedbackPush("sha1")
            h.gitlab.reply(
                h.gitlab.discussions
                    .single()
                    .id,
                "Good catch, will fix"
            )
            assertEquals(listOf(npe), h.feedbackPush("sha2"))
            assertEquals(1, h.gitlab.posts.size)
        }

    @Test
    fun `an unknown command gets exactly one help reply`() =
        runTest {
            val h = LifecycleHarness()
            h.feedbackPush("sha1")
            h.gitlab.reply(
                h.gitlab.discussions
                    .single()
                    .id,
                "@karandash frobnicate"
            )
            h.feedbackPush("sha2")
            h.feedbackPush("sha3")
            assertEquals(1, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertTrue("ignore" in h.gitlab.replies.single() && "wontfix" in h.gitlab.replies.single())
            assertEquals(0, h.gitlab.resolves.size)
        }

    @Test
    fun `a thread reopened after ignore is not resolved again`() =
        runTest {
            val h = LifecycleHarness()
            h.feedbackPush("sha1")
            val id = h.gitlab.discussions
                .single()
                .id
            h.gitlab.reply(id, "@karandash ignore")
            h.feedbackPush("sha2")
            h.gitlab.reopen(id)
            val notes = h.gitlab.discussions
                .single()
                .notes.size
            val kept = h.feedbackPush("sha3")
            assertEquals(1, h.gitlab.resolves.size, "resolves")
            assertEquals(
                notes,
                h.gitlab.discussions
                    .single()
                    .notes.size,
                "notes"
            )
            assertEquals(listOf(npe), kept)
            assertEquals(1, h.gitlab.posts.size, "posts")
        }

    @Test
    fun `why and ask commands do not get the help reply or dismiss the finding`() =
        runTest {
            val h = LifecycleHarness()
            h.feedbackPush("sha1")
            val id = h.gitlab.discussions
                .single()
                .id
            h.gitlab.reply(id, "@karandash why")
            h.gitlab.reply(id, "@karandash ask is x ever null?")
            val kept = h.feedbackPush("sha2")
            assertEquals(listOf(npe), kept)
            assertEquals(emptyList(), h.gitlab.replies)
            assertEquals(0, h.gitlab.resolves.size)
        }
}
