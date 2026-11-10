package io.karandash.providers.lifecycle

import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.providers.lifecycle.LifecycleHarness.Companion.finding
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Inline comment reconciliation against a stateful GitLab over several pushes (MockEngine, real GitLab service). */
class InlineReconcileScenarioTest {
    private val code = "fun main() {\n    val x = compute()\n    println(x!!)\n}\n"
    private val npe = finding("null-safety", "a.kt", 3, "println(x!!)", "Avoid `!!` on x")
    private val onFixedReply = buildJsonObject { put("inline", buildJsonObject { put("onFixed", "reply") }) }

    @Test
    fun `a head file the token cannot read keeps its thread open`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.gitlab.forbiddenFiles = setOf("a.kt")
            h.push("sha2", mapOf("a.kt" to code.replace("println(x!!)", "println(x ?: 0)")), emptyList())
            assertEquals(0, h.gitlab.resolves.size, "resolves")
            assertEquals(1, h.gitlab.posts.size, "posts")
        }

    @Test
    fun `max-per-run 0 posts nothing inline and lists every new finding`() =
        runTest {
            val config = buildJsonObject { put("inline", buildJsonObject { put("maxPerRun", JsonPrimitive(0)) }) }
            val h = LifecycleHarness(config = config)
            val other = finding("naming", "a.kt", 2, "val x = compute()", "Name x after what it holds")
            h.push("sha1", mapOf("a.kt" to code), listOf(npe, other))
            assertEquals(0, h.gitlab.posts.size, "posts")
            val summary = checkNotNull(h.gitlab.summary)
            assertTrue("0 open" in summary && "2 more not posted inline" in summary, summary)
            assertTrue("a.kt:2" in summary && "a.kt:3" in summary, summary)
        }

    @Test
    fun `re-push with shifted lines gives 0 reposts`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            val shifted = "import a.b\n\n$code"
            h.push("sha2", mapOf("a.kt" to shifted), listOf(npe.copy(location = Location.FileRef("a.kt", 5))))
            h.push("sha3", mapOf("a.kt" to shifted), listOf(npe.copy(location = Location.FileRef("a.kt", 5))))
            assertEquals(1, h.gitlab.posts.size, "posts")
            assertEquals(0, h.gitlab.resolves.size, "resolves")
        }

    @Test
    fun `a fix edits the bot note and resolves without a new note`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("sha2", mapOf("a.kt" to code.replace("println(x!!)", "println(x ?: 0)")), emptyList())
            assertEquals(1, h.gitlab.resolves.size, "resolves")
            assertEquals(0, h.gitlab.replies.size, h.gitlab.replies.toString())
            val edited = h.gitlab.noteEdits.single()
            assertTrue(edited.startsWith("✅ Fixed in sha2"), edited)
            assertTrue("~~" in edited && "karandash-finding" in edited, edited)
            assertEquals(1, h.gitlab.posts.size, "posts")
        }

    @Test
    fun `onFixed reply resolves with a fixed reply`() =
        runTest {
            val h = LifecycleHarness(config = onFixedReply)
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("sha2", mapOf("a.kt" to code.replace("println(x!!)", "println(x ?: 0)")), emptyList())
            assertEquals(1, h.gitlab.resolves.size, "resolves")
            assertTrue(
                h.gitlab.replies
                    .single()
                    .contains("Fixed in sha2"),
                h.gitlab.replies.toString()
            )
            assertEquals(0, h.gitlab.noteEdits.size, "edits")
        }

    @Test
    fun `a thread whose anchor is still at head survives without a finding`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("sha2", mapOf("a.kt" to code), emptyList())
            assertEquals(0, h.gitlab.resolves.size)
            assertEquals(0, h.gitlab.replies.size)
            assertEquals(1, h.gitlab.posts.size)
        }

    @Test
    fun `a thread on a file without head content survives`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("sha2", emptyMap(), emptyList())
            assertEquals(0, h.gitlab.resolves.size)
        }

    @Test
    fun `a human reply gets the note edited once and the thread left open`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.gitlab.reply(
                h.gitlab.discussions
                    .single()
                    .id,
                "Will do in a follow-up"
            )
            val fixed = mapOf("a.kt" to code.replace("println(x!!)", "println(x ?: 0)"))
            h.push("sha2", fixed, emptyList())
            h.push("sha3", fixed, emptyList())
            assertEquals(0, h.gitlab.resolves.size, "resolves")
            assertEquals(0, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertTrue(
                h.gitlab.noteEdits
                    .single()
                    .startsWith("✅ Fixed in sha2"),
                h.gitlab.noteEdits.toString()
            )
            assertEquals(1, h.gitlab.posts.size)
        }

    @Test
    fun `onFixed reply gives a human-replied thread looks fixed once instead of a resolve`() =
        runTest {
            val h = LifecycleHarness(config = onFixedReply)
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.gitlab.reply(
                h.gitlab.discussions
                    .single()
                    .id,
                "Will do in a follow-up"
            )
            val fixed = mapOf("a.kt" to code.replace("println(x!!)", "println(x ?: 0)"))
            h.push("sha2", fixed, emptyList())
            h.push("sha3", fixed, emptyList())
            assertEquals(0, h.gitlab.resolves.size, "resolves")
            assertEquals(1, h.gitlab.replies.size, h.gitlab.replies.toString())
            assertTrue(
                h.gitlab.replies
                    .single()
                    .contains("Looks fixed in sha2")
            )
            assertEquals(1, h.gitlab.posts.size)
        }

    @Test
    fun `a force-push keeping the code gives 0 reposts and 0 resolves`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("rewritten", mapOf("a.kt" to code), listOf(npe))
            assertEquals(1, h.gitlab.posts.size)
            assertEquals(0, h.gitlab.resolves.size)
        }

    @Test
    fun `a force-push dropping the code resolves the thread`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("rewritten", mapOf("a.kt" to "fun main() {}\n"), emptyList())
            assertEquals(1, h.gitlab.resolves.size)
        }

    @Test
    fun `a second instance of the same rule and message on another line is posted`() =
        runTest {
            val h = LifecycleHarness()
            val twice = "$code    println(y!!)\n"
            h.push("sha1", mapOf("a.kt" to twice), listOf(npe))
            val sibling = finding("null-safety", "a.kt", 5, "println(y!!)", "Avoid `!!` on x")
            h.push("sha2", mapOf("a.kt" to twice), listOf(npe, sibling))
            assertEquals(2, h.gitlab.posts.size, "posts")
            assertEquals(0, h.gitlab.resolves.size, "resolves")
        }

    @Test
    fun `the fixed instance resolves while its same message sibling stays open`() =
        runTest {
            val h = LifecycleHarness()
            val twice = "$code    println(y!!)\n"
            val sibling = finding("null-safety", "a.kt", 5, "println(y!!)", "Avoid `!!` on x")
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            val fixed = twice.replace("println(x!!)", "println(x ?: 0)")
            h.push("sha2", mapOf("a.kt" to fixed), listOf(sibling))
            assertEquals(2, h.gitlab.posts.size, "posts")
            assertEquals(
                listOf(
                    h.gitlab.discussions
                        .first()
                        .id
                ),
                h.gitlab.resolves
            )
            assertTrue(
                h.gitlab.discussions
                    .last()
                    .resolved
                    .not(),
                "the sibling thread stays open"
            )
        }

    @Test
    fun `a forged marker from a non-bot does not hide a finding`() =
        runTest {
            val h = LifecycleHarness()
            val forged = LifecycleHarness
                .report(listOf(npe))
                .inlineComments
                .single()
                .body
            h.gitlab.addDiscussion(forged, "a.kt", 3, author = "mallory")
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("sha2", mapOf("a.kt" to "fun main() {}\n"), emptyList())
            assertEquals(1, h.gitlab.posts.size, "posts")
            assertEquals(
                listOf(
                    h.gitlab.discussions
                        .last()
                        .id
                ),
                h.gitlab.resolves,
                "only the bot's own thread is resolved"
            )
        }

    @Test
    fun `a listing that fails once is retried`() =
        runTest {
            val h = LifecycleHarness()
            h.gitlab.failListings = 1
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            assertEquals(1, h.gitlab.posts.size, "posts")
        }

    @Test
    fun `a listing that fails twice posts nothing and says so then recovers`() =
        runTest {
            val h = LifecycleHarness()
            h.gitlab.failListings = 2
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            assertEquals(0, h.gitlab.posts.size, "posts")
            val summary = checkNotNull(h.gitlab.summary)
            assertTrue("Inline comments were skipped this run" in summary, summary)
            h.push("sha2", mapOf("a.kt" to code), listOf(npe))
            assertEquals(1, h.gitlab.posts.size, "posts after recovery")
            assertTrue("skipped" !in checkNotNull(h.gitlab.summary))
        }

    @Test
    fun `a Russian output config renders the delta and the listing notice in Russian`() =
        runTest {
            val h = LifecycleHarness(config = buildJsonObject { put("language", "ru") })
            h.gitlab.failListings = 2
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            assertTrue("не удалось получить список обсуждений" in checkNotNull(h.gitlab.summary), h.gitlab.summary)
            h.push("sha2", mapOf("a.kt" to code), listOf(npe))
            assertTrue("**Inline-ревью:** открытых — 1, исправлено — 0" in checkNotNull(h.gitlab.summary), h.gitlab.summary)
        }

    @Test
    fun `switching report language to Russian gives 0 reposts and 0 resolves`() =
        runTest {
            val gitlab = FakeGitLab()
            LifecycleHarness(gitlab).push("sha1", mapOf("a.kt" to code), listOf(npe))
            val ru = LifecycleHarness(gitlab, config = buildJsonObject { put("language", "ru") })
            ru.push("sha2", mapOf("a.kt" to code), listOf(npe.copy(message = "Не используйте `!!` для x")))
            ru.push("sha3", mapOf("a.kt" to code), listOf(npe.copy(message = "Не используйте `!!` для x")))
            assertEquals(1, gitlab.posts.size, "posts")
            assertEquals(0, gitlab.resolves.size, "resolves")
            assertTrue("открытых — 1, исправлено — 0" in checkNotNull(gitlab.summary), gitlab.summary)
        }

    @Test
    fun `a full cache hit still resolves fixed threads`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("sha2", mapOf("a.kt" to "fun main() {}\n"), emptyList(), changedFiles = emptySet())
            assertEquals(1, h.gitlab.resolves.size, "resolves")
        }

    @Test
    fun `a full cache hit posts no duplicates`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("sha2", mapOf("a.kt" to code), listOf(npe), changedFiles = emptySet())
            assertEquals(1, h.gitlab.posts.size, "posts")
            assertEquals(0, h.gitlab.resolves.size, "resolves")
        }

    @Test
    fun `a resolved thread is never reposted`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            val resolved = h.gitlab.discussions.map {
                it.copy(
                    resolved = true,
                    notes = it.notes.map { n ->
                        n.copy(resolved = true, resolvedBy = FakeGitLab.user(FakeGitLab.MEMBER))
                    }
                )
            }
            h.gitlab.discussions.clear()
            h.gitlab.discussions += resolved
            h.push("sha2", mapOf("a.kt" to code), listOf(npe))
            assertEquals(1, h.gitlab.posts.size)
        }

    @Test
    fun `a regression after auto-resolve is posted again`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("sha2", mapOf("a.kt" to code.replace("println(x!!)", "println(x ?: 0)")), emptyList())
            h.push("sha3", mapOf("a.kt" to code), listOf(npe))
            h.push("sha4", mapOf("a.kt" to code), listOf(npe))
            assertEquals(1, h.gitlab.resolves.size, "resolves")
            assertEquals(2, h.gitlab.posts.size, "posts")
        }

    @Test
    fun `a bot-resolved thread with a human reply is never reposted`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            h.push("sha2", mapOf("a.kt" to code.replace("println(x!!)", "println(x ?: 0)")), emptyList())
            h.gitlab.reply(
                h.gitlab.discussions
                    .single()
                    .id,
                "Thanks"
            )
            h.push("sha3", mapOf("a.kt" to code), listOf(npe))
            assertEquals(1, h.gitlab.posts.size, "posts")
        }

    @Test
    fun `new findings over max-per-run go to a collapsed list in the summary`() =
        runTest {
            val config = buildJsonObject { put("inline", buildJsonObject { put("maxPerRun", JsonPrimitive(2)) }) }
            val h = LifecycleHarness(config = config)
            val lines = (1..4).map { "val v$it = $it" }
            val findings = lines.mapIndexed { i, text -> finding("r", "b.kt", i + 1, text, "Finding $i") }
            h.push("sha1", mapOf("b.kt" to lines.joinToString("\n")), findings)
            assertEquals(2, h.gitlab.posts.size)
            val summary = checkNotNull(h.gitlab.summary)
            assertTrue("<details>" in summary && "b.kt:3" in summary && "b.kt:4" in summary, summary)
            assertTrue("2 open" in summary, summary)
        }

    @Test
    fun `an error is posted before a warning reported earlier when max-per-run is reached`() =
        runTest {
            val config = buildJsonObject { put("inline", buildJsonObject { put("maxPerRun", JsonPrimitive(1)) }) }
            val h = LifecycleHarness(config = config)
            val warning = finding("r", "b.kt", 1, "val a = 1", "A warning")
            val error = finding("r", "b.kt", 2, "val b = 2", "An error").copy(severity = Severity.ERROR)
            h.push("sha1", mapOf("b.kt" to "val a = 1\nval b = 2"), listOf(warning, error))
            assertTrue("An error" in h.gitlab.posts.single(), h.gitlab.posts.single())
            assertTrue("b.kt:1" in checkNotNull(h.gitlab.summary), h.gitlab.summary)
        }

    @Test
    fun `findings of one severity over max-per-run keep report order`() =
        runTest {
            val config = buildJsonObject { put("inline", buildJsonObject { put("maxPerRun", JsonPrimitive(1)) }) }
            val h = LifecycleHarness(config = config)
            val first = finding("r", "b.kt", 1, "val a = 1", "First")
            val second = finding("r", "b.kt", 2, "val b = 2", "Second")
            h.push("sha1", mapOf("b.kt" to "val a = 1\nval b = 2"), listOf(first, second))
            assertTrue("First" in h.gitlab.posts.single(), h.gitlab.posts.single())
        }

    @Test
    fun `a comment that fails to post is not counted as new`() =
        runTest {
            val h = LifecycleHarness()
            // The first failure fails the batch review; the comments are then posted one by one and the first fails
            h.gitlab.failPosts = 2
            val a = finding("r", "b.kt", 1, "val a = 1", "First")
            val b = finding("r", "b.kt", 2, "val b = 2", "Second")
            h.push("sha1", mapOf("b.kt" to "val a = 1\nval b = 2"), listOf(a, b))
            assertEquals(1, h.gitlab.posts.size, "posts")
            assertTrue("1 open" in checkNotNull(h.gitlab.summary), h.gitlab.summary)
        }

    @Test
    fun `a thread that fails to resolve is not counted as fixed`() =
        runTest {
            val h = LifecycleHarness()
            val a = finding("r", "b.kt", 1, "val a = 1", "First")
            h.push("sha1", mapOf("b.kt" to "val a = 1"), listOf(a))
            h.gitlab.failResolves = 1
            h.push("sha2", mapOf("b.kt" to "val c = 3"), emptyList())
            assertEquals(emptyList(), h.gitlab.resolves, "resolves")
            assertFalse("fixed" in checkNotNull(h.gitlab.summary), h.gitlab.summary)
        }

    @Test
    fun `a thread that resolves is counted as fixed`() =
        runTest {
            val h = LifecycleHarness()
            val a = finding("r", "b.kt", 1, "val a = 1", "First")
            h.push("sha1", mapOf("b.kt" to "val a = 1"), listOf(a))
            h.push("sha2", mapOf("b.kt" to "val c = 3"), emptyList())
            assertTrue("1 fixed" in checkNotNull(h.gitlab.summary), h.gitlab.summary)
        }

    @Test
    fun `the summary is edited in place with the new open and fixed delta`() =
        runTest {
            val h = LifecycleHarness()
            h.push("sha1", mapOf("a.kt" to code), listOf(npe))
            val other = finding("naming", "a.kt", 2, "val x = compute()", "Name x after what it holds")
            h.push("sha2", mapOf("a.kt" to code.replace("println(x!!)", "println(x ?: 0)")), listOf(other))
            val summary = checkNotNull(h.gitlab.summary)
            assertTrue("1 open, 1 fixed" in summary, summary)
        }
}
