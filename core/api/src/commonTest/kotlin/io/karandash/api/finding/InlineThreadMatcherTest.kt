package io.karandash.api.finding

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.utils.finding.AnchorFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineThreadMatcherTest {
    private fun finding(
        message: String,
        line: Int,
        lineText: String = "code $line",
        path: String = "src/A.kt",
        rule: String = "ai-review-comments",
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = rule,
        location = Location.FileRef(path, line),
        metadata = mapOf(WellKnownMetadataKeys.ANCHOR_FINGERPRINT to AnchorFingerprint.generate(rule, path, lineText)),
    )

    /** A marker without `fpv`. */
    private fun legacyRef(finding: Finding): InlineFindingRef =
        InlineFindingMarker.parse(InlineFindingMarker.of(finding.copy(metadata = emptyMap()))).single().also {
            assertEquals("", it.fpv)
        }

    @Test
    fun `legacy marker thread is matched not reposted`() {
        val current = finding("Null check is missing", line = 10)
        val other = finding("Unrelated concern", line = 40)

        val result = InlineThreadMatcher.match(
            findings = listOf(current),
            threads = mapOf("t-current" to listOf(legacyRef(current)), "t-other" to listOf(legacyRef(other))),
        )

        assertEquals(mapOf(current to "t-current"), result.matched)
        assertEquals(emptyList(), result.unmatchedFindings)
        assertEquals(listOf("t-other"), result.unmatchedThreads)
    }

    @Test
    fun `fpv matches a thread after the line moved and the message was reworded`() {
        val posted = finding("Null check is missing", line = 10, lineText = "val x = y!!")
        val now = finding("Missing null check on y", line = 14, lineText = "  val x =  y!!")

        val result = InlineThreadMatcher.match(listOf(now), mapOf("t" to InlineFindingMarker.parse(InlineFindingMarker.of(posted))))

        assertEquals(mapOf(now to "t"), result.matched)
    }

    @Test
    fun `a new finding on another line text stays unmatched`() {
        val posted = finding("Null check is missing", line = 10, lineText = "val x = y!!")
        val fresh = finding("Leaks the stream", line = 30, lineText = "val s = open()")

        val result = InlineThreadMatcher.match(
            listOf(posted, fresh),
            mapOf("t" to InlineFindingMarker.parse(InlineFindingMarker.of(posted))),
        )

        assertEquals(mapOf(posted to "t"), result.matched)
        assertEquals(listOf(fresh), result.unmatchedFindings)
        assertTrue(result.unmatchedThreads.isEmpty())
    }

    @Test
    fun `every current finding matches a 1_41_1 marker set with 0 unmatched`() {
        val current = listOf(
            finding("Null check is missing", line = 10),
            finding("Use a constant here", line = 22),
            finding("Close the stream", line = 31, path = "src/B.kt"),
            finding("Function is too long", line = 5, rule = "ai-code-quality"),
        )
        // findings on one line grouped into one note, and lines have since moved by 3 (fp misses them)
        val posted = current.map { it.copy(location = (it.location as Location.FileRef).copy(line = it.lineOrZero() - 3)) }
        val threads = mapOf(
            "t1" to listOf(legacyRef(posted[0]), legacyRef(posted[1])),
            "t2" to listOf(legacyRef(posted[2])),
            "t3" to listOf(legacyRef(posted[3])),
        )

        val result = InlineThreadMatcher.match(current, threads)

        assertEquals(current.toSet(), result.matched.keys)
        assertEquals(emptyList(), result.unmatchedFindings)
        assertEquals(emptyList(), result.unmatchedThreads)
    }

    @Test
    fun `comments match threads by the refs in their bodies`() {
        val kept = finding("Null check is missing", line = 10, lineText = "val x = y!!")
        val fresh = finding("Leaks the stream", line = 30, lineText = "val s = open()")
        val bodies = listOf(kept, fresh).associateWith { "note\n${InlineFindingMarker.of(it)}" }

        val result = InlineThreadMatcher.match(
            bodies.values.toList(),
            { InlineFindingMarker.parse(it) },
            mapOf("t" to listOf(InlineFindingMarker.refOf(kept.copy(message = "Reworded")))),
        )

        assertEquals(mapOf(bodies.getValue(kept) to "t"), result.matched)
        assertEquals(listOf(bodies.getValue(fresh)), result.unmatchedFindings)
    }

    @Test
    fun `refs without fpv on both sides do not match by fpv`() {
        val a = finding("First concern", line = 1).copy(metadata = emptyMap())
        val b = finding("Second concern", line = 2).copy(metadata = emptyMap())

        val result = InlineThreadMatcher.match(listOf(b), mapOf("t" to listOf(InlineFindingMarker.refOf(a))))

        assertEquals(emptyMap(), result.matched)
    }

    @Test
    fun `a same rule and message sibling on another line is not swallowed by the posted thread`() {
        val posted = finding("Null check is missing", line = 10, lineText = "val x = y!!")
        val sibling = finding("Null check is missing", line = 30, lineText = "val z = w!!")

        val result = InlineThreadMatcher.match(
            listOf(posted, sibling),
            mapOf("t" to InlineFindingMarker.parse(InlineFindingMarker.of(posted))),
        )

        assertEquals(mapOf(posted to "t"), result.matched)
        assertEquals(listOf(sibling), result.unmatchedFindings)
    }

    @Test
    fun `a same message sibling stays unmatched when the posted instance is fixed`() {
        val fixed = finding("Null check is missing", line = 10, lineText = "val x = y!!")
        val sibling = finding("Null check is missing", line = 30, lineText = "val z = w!!")

        val result = InlineThreadMatcher.match(listOf(sibling), mapOf("t" to InlineFindingMarker.parse(InlineFindingMarker.of(fixed))))

        assertEquals(emptyMap(), result.matched)
        assertEquals(listOf("t"), result.unmatchedThreads)
    }

    @Test
    fun `a legacy ref absorbs one same message finding and the rest stay unmatched`() {
        val first = finding("Null check is missing", line = 10)
        val second = finding("Null check is missing", line = 20)
        val third = finding("Null check is missing", line = 30)

        val result = InlineThreadMatcher.match(listOf(first, second, third), mapOf("t" to listOf(legacyRef(first))))

        assertEquals(mapOf(first to "t"), result.matched)
        assertEquals(listOf(second, third), result.unmatchedFindings)
    }

    @Test
    fun `fpv-less findings each adopt their own ref of a grouped legacy note`() {
        val first = finding("Null check is missing", line = 10).copy(metadata = emptyMap())
        val second = finding("Null check is missing", line = 10).copy(metadata = emptyMap(), message = "Null check is missing ")

        val result = InlineThreadMatcher.match(listOf(first, second), mapOf("t" to listOf(legacyRef(first), legacyRef(first))))

        assertEquals(setOf(first, second), result.matched.keys)
    }

    private fun Finding.lineOrZero(): Int = (location as? Location.FileRef)?.line ?: 0

    @Test
    fun `a message translated after a report language change keeps the anchor fingerprint and its thread`() {
        val english = finding("Null check is missing", line = 10, lineText = "val x = y!!")
        val russian = finding("Нет проверки на null", line = 10, lineText = "val x = y!!")
        val posted = InlineFindingMarker.parse(InlineFindingMarker.of(english)).single()

        assertEquals(posted.fpv, InlineFindingMarker.refOf(russian).fpv)
        assertEquals(mapOf(russian to "t"), InlineThreadMatcher.match(listOf(russian), mapOf("t" to listOf(posted))).matched)
    }
}
