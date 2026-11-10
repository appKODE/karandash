package io.karandash.utils

import io.karandash.duration.KarandashDuration
import io.karandash.js.JsUtils
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Location
import io.karandash.utils.finding.toDisplayString
import io.karandash.utils.finding.toFilePosition
import io.karandash.utils.mr.commitsByAuthor
import io.karandash.utils.mr.hasSquashableCommits
import io.karandash.utils.mr.isConventional
import io.karandash.utils.mr.isFixup
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class CommonHelpersContractTest {
    /** Reference edit distance (recursive with memo) to check the engine against. */
    private fun distance(
        a: String,
        b: String,
    ): Int {
        val memo = mutableMapOf<Pair<Int, Int>, Int>()

        fun d(
            i: Int,
            j: Int,
        ): Int =
            memo.getOrPut(i to j) {
                when {
                    i == 0 -> j
                    j == 0 -> i
                    else -> minOf(d(i - 1, j) + 1, d(i, j - 1) + 1, d(i - 1, j - 1) + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
            }
        return d(a.length, b.length)
    }

    @Test
    fun `suggestions are the closest candidates within three edits`() {
        val random = Random(9)

        fun word() = List(random.nextInt(0, 7)) { "abcAB"[random.nextInt(5)] }.joinToString("")
        repeat(200) {
            val input = word()
            val candidates = List(random.nextInt(0, 8)) { word() }

            val suggested = SuggestionEngine.suggest(input, candidates)

            val distances = suggested.map { distance(input.lowercase(), it.lowercase()) }
            assertTrue(suggested.size <= 3)
            assertTrue(distances.all { it <= 3 }, "$input -> $suggested")
            assertEquals(distances.sorted(), distances)
            val eligible = candidates.count { distance(input.lowercase(), it.lowercase()) <= 3 }
            assertEquals(minOf(3, eligible), suggested.size)
            assertEquals(suggested, SuggestionEngine.findClosest(input, candidates))
        }
        assertEquals(listOf("gitlab"), SuggestionEngine.suggest("GitLub", listOf("gitlab", "bitbucket")))
    }

    @Test
    fun `js escaping leaves no raw quote or control character`() {
        val escaped = JsUtils.escapeForJs("a\\b'c\"d\ne\rf\tg")

        assertEquals("a\\\\b\\'c\\\"d\\ne\\rf\\tg", escaped)
        assertFalse(escaped.any { it == '\n' || it == '\r' || it == '\t' })
        assertEquals(listOf("a", "b"), JsUtils.stringListFromJson("""["a","b"]"""))
    }

    @Test
    fun `js escaping turns other control characters and line separators 2028 2029 into unicode escapes`() {
        assertEquals("a\\u0000b\\u001bc\\u2028d\\u2029e", JsUtils.escapeForJs("a\u0000b\u001Bc d e"))
    }

    @Test
    fun `json quoting keeps single quotes raw and round-trips through a JSON parser`() {
        val raw = "it's \"q\"\\\n\u0000"
        val quoted = JsUtils.quoteJson(raw)

        assertEquals("\"it's \\\"q\\\"\\\\\\n\\u0000\"", quoted)
        assertEquals(listOf(raw), JsUtils.stringListFromJson("[$quoted]"))
    }

    @Test
    fun `locations format for display and inline positions`() {
        assertEquals("a.kt:3", Location.FileRef("a.kt", 3).toDisplayString())
        assertEquals("a.kt", Location.FileRef("a.kt").toDisplayString())
        assertEquals("commit:abc", Location.CommitRef("abc").toDisplayString())
        assertEquals("a.kt" to 3, Location.FileRef("a.kt", 3).toFilePosition())
        assertNull(Location.CommitRef("abc").toFilePosition())
    }

    @Test
    fun `commit helpers classify authors fixups and conventional messages`() {
        fun commit(
            message: String,
            author: String = "Ann",
        ) = Commit("s", message, CommitAuthor(author, "a@x"), "2024-01-01T00:00:00Z")
        val commits = listOf(commit("feat(x)!: a"), commit("fixup! a", "bob"), commit("squash! b"), commit("wip"))
        val mr = MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = null,
            sourceBranch = "f",
            targetBranch = "main",
            author = User("u", "U"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = commits,
            changes = emptyList(),
        )

        assertEquals(3, mr.commitsByAuthor("ANN").size)
        assertFalse(mr.hasSquashableCommits())
        assertTrue(mr.hasSquashableCommits(maxCommits = 3))
        assertEquals(listOf(false, true, true, false), commits.map { it.isFixup() })
        assertEquals(listOf(true, false, false, false), commits.map { it.isConventional() })
    }

    @Test
    fun `durations expose truncated units`() {
        val duration = KarandashDuration.ofMillis(2.hours.inWholeMilliseconds + 61_500)

        assertEquals(7261, duration.seconds)
        assertEquals(121, duration.minutes)
        assertEquals(2, duration.hours)
        assertEquals(duration.millis, duration.toKotlinDuration().inWholeMilliseconds)
        assertEquals(KarandashDuration.ofHours(1), KarandashDuration.ofMinutes(60))
    }
}
