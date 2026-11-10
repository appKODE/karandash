@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.core

import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.ReviewAward
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.api.run.MergeRequestThreads
import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadFeedbackTest {
    private val finding = Finding(Severity.WARNING, "Avoid bang on x", "null-safety", Location.FileRef("a.kt", 3))

    private class FakeThreads(
        var threads: List<ReviewThread>,
        val members: Set<String> = setOf("alice"),
    ) : MergeRequestThreads {
        val resolved = mutableListOf<String>()
        val replies = mutableListOf<String>()
        var memberCalls = 0

        override suspend fun list(): List<ReviewThread>? = threads

        override suspend fun botUsername(): String = "bot"

        override suspend fun isMember(username: String): Boolean {
            memberCalls++
            return username in members
        }

        override suspend fun resolve(
            threadId: String,
            note: String,
        ): Result<Unit> {
            resolved += threadId
            threads = threads.map {
                if (it.id == threadId) it.copy(resolved = true, notes = it.notes + ReviewNote("bot-resolve-$threadId", note, "bot")) else it
            }
            return Result.success(Unit)
        }

        override suspend fun reply(
            threadId: String,
            body: String,
        ): Result<Unit> {
            replies += body
            threads = threads.map { if (it.id == threadId) it.copy(notes = it.notes + ReviewNote("bot-reply", body, "bot")) else it }
            return Result.success(Unit)
        }
    }

    private val anchored = finding.copy(metadata = mapOf(WellKnownMetadataKeys.ANCHOR_FINGERPRINT to "fp1"))

    private fun thread(
        vararg replies: Pair<String, String>,
        of: Finding = finding,
    ) = ReviewThread(
        id = "t1",
        notes = listOf(ReviewNote("n0", "body\n${InlineFindingMarker.of(of)}", "bot")) +
            replies.mapIndexed { i, (author, text) -> ReviewNote("n${i + 1}", text, author) },
        position = ReviewThreadPosition(newPath = "a.kt", newLine = 3),
    )

    private suspend fun ingest(threads: FakeThreads): FeedbackStore =
        FeedbackStore().also { ThreadFeedback.ingest(it, threads, NoOpLogger) }

    @Test
    fun `a member command dismisses with its reason and resolves the thread`() =
        runTest {
            val threads = FakeThreads(listOf(thread("alice" to "@karandash wontfix")))
            val store = ingest(threads)
            assertTrue(store.filterFindings(listOf(finding)).kept.isEmpty())
            assertEquals("wontfix", store.toSerializable().single().reason)
            assertEquals(listOf("t1"), threads.resolved)
        }

    @Test
    fun `a command on a resolved thread dismisses without resolving again`() =
        runTest {
            val threads = FakeThreads(listOf(thread("alice" to "@karandash ignore").copy(resolved = true)))
            val store = ingest(threads)
            assertEquals(1, store.dismissCount())
            assertTrue(threads.resolved.isEmpty())
        }

    @Test
    fun `a non-member reply is not learned from`() =
        runTest {
            val threads = FakeThreads(listOf(thread("mallory" to "@karandash ignore", "mallory" to "false positive")))
            val store = ingest(threads)
            assertEquals(0, store.dismissCount())
            assertTrue(threads.resolved.isEmpty())
            assertEquals(1, threads.memberCalls, "membership is checked once per author")
        }

    @Test
    fun `a keyword rejection from a member is recorded`() =
        runTest {
            val store = ingest(FakeThreads(listOf(thread("alice" to "False positive, x is never null"))))
            assertEquals(1, store.dismissCount())
        }

    @Test
    fun `an unknown reply goes to the judge and its verdict is recorded`() =
        runTest {
            val asked = mutableListOf<Pair<String, String>>()
            val judge = ReplyJudge { reply, excerpt -> ReplyVerdict.REJECTED.also { asked += reply to excerpt } }
            val threads = FakeThreads(listOf(thread("alice" to "see the upstream handler")))
            assertEquals(0, ingest(threads).dismissCount())
            val store = FeedbackStore().also { ThreadFeedback.ingest(it, threads, NoOpLogger, judge = judge) }
            assertEquals(1, store.dismissCount())
            val (reply, excerpt) = asked.single()
            assertEquals("see the upstream handler", reply)
            assertEquals("body", excerpt)
        }

    @Test
    fun `a classified reply never reaches the judge`() =
        runTest {
            val judge = ReplyJudge { _, _ -> error("judge called") }
            val store = FeedbackStore()
            ThreadFeedback.ingest(
                store,
                FakeThreads(listOf(thread("alice" to "False positive, x is never null"))),
                NoOpLogger,
                judge = judge
            )
            assertEquals(1, store.dismissCount())
        }

    private fun reacted(
        thread: ReviewThread,
        vararg awards: Pair<String, String>,
    ) = thread.copy(
        notes = thread.notes.mapIndexed { i, n ->
            if (i ==
                0
            ) {
                n.copy(awards = awards.map { (name, user) -> ReviewAward(name, user) })
            } else {
                n
            }
        },
    )

    @Test
    fun `a thumbs down on the bot note keeps an accepting reply from accepting`() =
        runTest {
            val agreed = ingest(FakeThreads(listOf(thread("alice" to "fixed", of = anchored))))
            assertEquals(1, agreed.acceptedAnchors().size)
            val vetoed = ingest(FakeThreads(listOf(reacted(thread("alice" to "fixed", of = anchored), "thumbsdown_tone2" to "bob"))))
            assertTrue(vetoed.acceptedAnchors().isEmpty())
            val botOnly = ingest(FakeThreads(listOf(reacted(thread("alice" to "fixed", of = anchored), "thumbsdown" to "bot"))))
            assertEquals(1, botOnly.acceptedAnchors().size, "the bot's own award is no reaction")
        }

    @Test
    fun `a thumbs up on the bot note keeps a dismissal out and the judge is not asked`() =
        runTest {
            val judge = ReplyJudge { _, _ -> error("judge called") }
            val store = FeedbackStore()
            val threads = FakeThreads(listOf(reacted(thread("alice" to "False positive, x is never null"), "thumbsup" to "bob")))
            ThreadFeedback.ingest(store, threads, NoOpLogger, judge = judge)
            assertEquals(0, store.dismissCount())
        }

    @Test
    fun `a judge verdict against the reaction is dropped`() =
        runTest {
            val judge = ReplyJudge { _, _ -> ReplyVerdict.REJECTED }
            val vetoed = FeedbackStore()
            ThreadFeedback.ingest(
                vetoed,
                FakeThreads(listOf(reacted(thread("alice" to "see upstream"), "thumbsup" to "bob"))),
                NoOpLogger,
                judge = judge
            )
            assertEquals(0, vetoed.dismissCount())
            val agreed = FeedbackStore()
            ThreadFeedback.ingest(
                agreed,
                FakeThreads(listOf(reacted(thread("alice" to "see upstream"), "thumbsdown" to "bob"))),
                NoOpLogger,
                judge = judge
            )
            assertEquals(1, agreed.dismissCount())
        }

    @Test
    fun `a cited sha accepts only when it is an MR commit`() =
        runTest {
            val reply = "Добавлен clamp в 1a2b3c4d"

            suspend fun accepted(commits: Set<String>?) =
                FeedbackStore()
                    .also {
                        ThreadFeedback.ingest(
                            it,
                            FakeThreads(listOf(thread("alice" to reply, of = anchored))),
                            NoOpLogger,
                            commits = commits
                        )
                    }.acceptedAnchors()
                    .size
            assertEquals(1, accepted(setOf("1A2B3C4D5E6F7a8b9c0d")))
            assertEquals(0, accepted(setOf("ffffffff00000000")))
            assertEquals(0, accepted(null))
        }

    @Test
    fun `an unknown command gets one help reply across runs`() =
        runTest {
            val threads = FakeThreads(listOf(thread("alice" to "@karandash frobnicate")))
            ingest(threads)
            ingest(threads)
            assertEquals(1, threads.replies.size)
            assertTrue(ThreadFeedback.HELP_MARKER in threads.replies.single())
        }

    @Test
    fun `bot notes and threads without markers are skipped`() =
        runTest {
            val plain =
                ReviewThread("t2", listOf(ReviewNote("p", "a human thread", "alice"), ReviewNote("q", "@karandash ignore", "alice")))
            val threads = FakeThreads(listOf(plain, thread("bot" to "@karandash ignore")))
            assertEquals(0, ingest(threads).dismissCount())
        }

    @Test
    fun `two unknown commands in one run get one help reply`() =
        runTest {
            val threads = FakeThreads(listOf(thread("alice" to "@karandash frobnicate", "alice" to "@karandash wat")))
            ingest(threads)
            assertEquals(1, threads.replies.size, threads.replies.toString())
        }

    @Test
    fun `two ignore replies in one run resolve once`() =
        runTest {
            val threads = FakeThreads(listOf(thread("alice" to "@karandash ignore", "alice" to "@karandash ignore")))
            ingest(threads)
            assertEquals(listOf("t1"), threads.resolved)
        }

    @Test
    fun `a command is applied once and keeps its dismissal while the thread stays resolved`() =
        runTest {
            val threads = FakeThreads(listOf(thread("alice" to "@karandash ignore")))
            ingest(threads)
            val store = ingest(threads)
            assertEquals(listOf("t1"), threads.resolved)
            assertTrue(store.filterFindings(listOf(finding)).kept.isEmpty())
        }

    @Test
    fun `a thread reopened after a command is not resolved again and its finding is raised`() =
        runTest {
            val threads = FakeThreads(listOf(thread("alice" to "@karandash ignore")))
            ingest(threads)
            threads.threads = threads.threads.map { it.copy(resolved = false) }
            val notes = threads.threads
                .single()
                .notes.size
            val store = ingest(threads)
            assertEquals(listOf("t1"), threads.resolved)
            assertEquals(
                notes,
                threads.threads
                    .single()
                    .notes.size
            )
            assertEquals(listOf(finding), store.filterFindings(listOf(finding)).kept)
        }

    @Test
    fun `a command in a thread whose marker note a non-bot forged is not heard`() =
        runTest {
            val forged = thread("alice" to "@karandash ignore").let { t ->
                t.copy(notes = listOf(t.notes.first().copy(author = "mallory")) + t.notes.drop(1))
            }
            val threads = FakeThreads(listOf(forged), members = setOf("alice", "mallory"))
            assertEquals(0, ingest(threads).dismissCount())
            assertTrue(threads.resolved.isEmpty())
        }

    @Test
    fun `a forged resolved-by marker from a non-bot does not spend the command`() =
        runTest {
            val marker = "<!-- karandash-resolved-by:n1 -->"
            val threads = FakeThreads(listOf(thread("alice" to "@karandash ignore", "mallory" to marker)))
            ingest(threads)
            assertEquals(listOf("t1"), threads.resolved)
        }

    @Test
    fun `a forged help marker from a non-bot does not suppress the help reply`() =
        runTest {
            val threads = FakeThreads(listOf(thread("alice" to "@karandash nope", "mallory" to ThreadFeedback.HELP_MARKER)))
            ingest(threads)
            assertEquals(1, threads.replies.size)
        }

    @Test
    fun `a command in a person-resolved thread stays spent after a reopen`() =
        runTest {
            val threads = FakeThreads(listOf(thread("alice" to "@karandash resolve").copy(resolved = true)))
            ingest(threads)
            threads.threads = threads.threads.map { it.copy(resolved = false) }
            val store = ingest(threads)
            assertTrue(threads.resolved.isEmpty())
            assertEquals(listOf(finding), store.filterFindings(listOf(finding)).kept)
        }
}
