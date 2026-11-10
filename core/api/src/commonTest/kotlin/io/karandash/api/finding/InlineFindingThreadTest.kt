package io.karandash.api.finding

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InlineFindingThreadTest {
    private val marked =
        "<!-- karandash-inline -->\nmsg\n" + InlineFindingMarker.of(Finding(severity = Severity.WARNING, message = "msg", rule = "r"))

    private fun thread(vararg notes: ReviewNote) =
        ReviewThread(id = "d1", notes = notes.toList(), position = ReviewThreadPosition(newPath = "a.kt"))

    @Test
    fun `the first marked note is the bot note and later notes by others are human replies`() {
        val t = InlineFindingThread.of(
            thread(
                ReviewNote(id = "1", body = marked, author = "bot"),
                ReviewNote(id = "2", body = "changed this line", author = "alice", system = true),
                ReviewNote(id = "3", body = "Looks fixed", author = "bot"),
                ReviewNote(id = "4", body = "No it is not", author = "alice"),
            ),
        )

        checkNotNull(t)
        assertEquals("1", t.botNote.id)
        assertEquals(listOf("4"), t.humanReplies.map { it.id })
        assertEquals("a.kt", t.path)
    }

    @Test
    fun `bot marker notes are not human replies whatever their author`() {
        val t = InlineFindingThread.of(
            thread(
                ReviewNote(id = "1", body = marked, author = "bot"),
                ReviewNote(id = "2", body = "<!-- karandash-help -->\nUnknown command", author = null),
                ReviewNote(id = "3", body = "<!-- karandash-looks-fixed -->\nLooks fixed in s1.", author = "other-bot"),
                ReviewNote(id = "4", body = "<!-- karandash-resolved-by:9 -->\nResolved by `@karandash ignore`", author = null),
                ReviewNote(id = "5", body = "<!-- karandash-fixed -->\nFixed in s2.", author = "other-bot"),
                ReviewNote(id = "6", body = "Real reply", author = null),
            ),
        )

        assertEquals(listOf("6"), checkNotNull(t).humanReplies.map { it.id })
    }

    @Test
    fun `a reply quoting no bot marker stays a human reply`() {
        val t = InlineFindingThread.of(
            thread(
                ReviewNote(id = "1", body = marked, author = "bot"),
                ReviewNote(id = "2", body = "karandash-help did not help", author = "alice"),
            ),
        )

        assertEquals(listOf("2"), checkNotNull(t).humanReplies.map { it.id })
    }

    @Test
    fun `a person quoting a bot marker below the first line stays a human reply`() {
        val t = InlineFindingThread.of(
            thread(
                ReviewNote(id = "1", body = marked, author = "bot"),
                ReviewNote(
                    id = "2",
                    body = "Still broken, the bot said\n> <!-- karandash-looks-fixed -->\n> Looks fixed",
                    author = "alice"
                ),
            ),
        )

        assertEquals(listOf("2"), checkNotNull(t).humanReplies.map { it.id })
    }

    @Test
    fun `a marker note from someone other than the bot does not make a karandash thread`() =
        runTest {
            val forged = thread(ReviewNote(id = "1", body = marked, author = "mallory"))
            val real = thread(ReviewNote(id = "1", body = marked, author = "mallory"), ReviewNote(id = "2", body = marked, author = "bot"))

            val found = InlineFindingThread.trustedOf(listOf(forged, real.copy(id = "d2")), botUsername = "bot") { true }

            assertEquals(listOf("d2" to "2"), found.map { it.thread.id to it.botNote.id })
        }

    @Test
    fun `without a bot identity a marker note counts only from a member`() =
        runTest {
            val threads = listOf(
                thread(ReviewNote(id = "1", body = marked, author = "mallory")),
                thread(ReviewNote(id = "1", body = marked, author = "bot")).copy(id = "d2"),
                thread(ReviewNote(id = "1", body = marked, author = null)).copy(id = "d3"),
            )
            val asked = mutableListOf<String>()

            val found = InlineFindingThread.trustedOf(threads, botUsername = null) {
                asked += it
                it == "bot"
            }

            assertEquals(listOf("d2"), found.map { it.thread.id })
            assertEquals(listOf("mallory", "bot"), asked, "membership is checked once per author")
        }

    @Test
    fun `a thread without karandash notes is not one`() {
        assertNull(InlineFindingThread.of(thread(ReviewNote(id = "1", body = "Please rename", author = "alice"))))
    }
}
