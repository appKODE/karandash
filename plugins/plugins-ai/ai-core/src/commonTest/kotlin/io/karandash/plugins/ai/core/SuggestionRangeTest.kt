package io.karandash.plugins.ai.core

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.pipeline.AiMetadataTransformer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A committable suggestion replaces exactly the added lines its existing code occupies, or is not offered. */
class SuggestionRangeTest {
    private val added = mapOf(
        10 to "val a = load()",
        11 to "val b = a.name",
        12 to "use(b)",
    )

    private fun finding(
        line: Int,
        existing: String?,
        endLine: Int? = null,
    ) = Finding(
        Severity.WARNING,
        "Null name",
        "review-comments",
        Location.FileRef("src/A.kt", line, endLine),
        metadata = buildMap {
            existing?.let { put(AiFindingMetadataKeys.EXISTING_CODE, it) }
            put(AiFindingMetadataKeys.SUGGESTED_CODE, "val b = a.name.orEmpty()")
        },
    )

    private fun pipeline(f: Finding): Finding? =
        AnchorResolver.resolve(f, added)?.let { AiMetadataTransformer.transform(listOf(it)).single() }

    @Test
    fun `a suggestion whose existing code is not on added lines loses its committable block but the finding survives`() {
        val bad = checkNotNull(pipeline(finding(11, "val b = a.name\nnotInTheDiff()")))
        assertNull(bad.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])

        val good = checkNotNull(pipeline(finding(11, "val b = a.name")))
        assertNotNull(good.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
        assertEquals(Location.FileRef("src/A.kt", 11), good.location)
    }

    @Test
    fun `a two line existing snippet off by one line is moved onto the lines it matches`() {
        val f = checkNotNull(pipeline(finding(12, "val a = load()\nval b = a.name")))

        assertEquals(Location.FileRef("src/A.kt", 10, 11), f.location)
        assertNotNull(f.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
    }

    @Test
    fun `a model end line wider than the existing code is narrowed to it`() {
        val f = checkNotNull(pipeline(finding(11, "val b = a.name", endLine = 12)))

        assertEquals(Location.FileRef("src/A.kt", 11), f.location)
    }

    @Test
    fun `a suggestion without existing code may not cover a line the MR did not add`() {
        assertNull(checkNotNull(pipeline(finding(11, null, endLine = 13))).metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
        assertNotNull(checkNotNull(pipeline(finding(11, null, endLine = 12))).metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
    }

    private fun pair(
        line: Int,
        existing: String,
    ): List<Finding> {
        val loc = Location.FileRef("src/A.kt", line)
        val review = Finding(Severity.WARNING, "Null name", "review-comments", loc, metadata = mapOf(AiFindingMetadataKeys.AI_MODEL to "m"))
        val companion = Finding(
            Severity.WARNING,
            "Code suggestion (BUG)",
            AiRuleIds.CODE_SUGGESTIONS,
            loc,
            metadata = mapOf(
                AiFindingMetadataKeys.AI_MODEL to "m",
                AiFindingMetadataKeys.EXISTING_CODE to existing,
                AiFindingMetadataKeys.SUGGESTED_CODE to "val b = a.name.orEmpty()",
            ),
        )
        return listOf(review, companion)
    }

    @Test
    fun `a companion suggestion moved onto its existing code still merges into its review comment`() {
        val out = AiMetadataTransformer.transform(pair(12, "val b = a.name").mapNotNull { AnchorResolver.resolve(it, added) })

        val only = out.single()
        assertEquals("review-comments", only.rule)
        assertEquals(Location.FileRef("src/A.kt", 11), only.location)
        assertNotNull(only.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
    }

    @Test
    fun `a companion suggestion whose existing code is not on added lines is dropped and the review comment stays`() {
        val out = AiMetadataTransformer.transform(pair(11, "notInTheDiff()").mapNotNull { AnchorResolver.resolve(it, added) })

        val only = out.single()
        assertEquals("review-comments", only.rule)
        assertNull(only.metadata[AiFindingMetadataKeys.SUGGESTED_CODE])
    }

    @Test
    fun `property applying a surviving suggestion replaces exactly its existing code`() {
        val rnd = Random(7_7)
        val pool = listOf("val a = 1", "call(x)", "return y", "if (z) go()", "val b = a + 1", "log(a)", "}", "")
        repeat(500) {
            val head = List(rnd.nextInt(3, 15)) { pool.random(rnd) }
            val addedLines = head.indices.filter { rnd.nextInt(4) > 0 }.associate { (it + 1) to head[it] }
            val start = rnd.nextInt(head.size)
            val existing = head.subList(start, minOf(head.size, start + rnd.nextInt(1, 4))).joinToString("\n")
            val proposed = "fixed(${rnd.nextInt(100)})\nmore()"
            val raw = Finding(
                Severity.WARNING,
                "m",
                "review-comments",
                Location.FileRef("src/A.kt", (start + 1 + rnd.nextInt(-3, 4)).coerceAtLeast(1)),
                metadata = mapOf(AiFindingMetadataKeys.EXISTING_CODE to existing, AiFindingMetadataKeys.SUGGESTED_CODE to proposed),
            )
            val out = AnchorResolver.resolve(raw, addedLines)?.let { AiMetadataTransformer.transform(listOf(it)).single() }
            val loc = out?.location as? Location.FileRef
            if (out?.metadata?.get(AiFindingMetadataKeys.SUGGESTED_CODE) == null) return@repeat
            val first = checkNotNull(loc?.line) { "a file-level finding kept a committable suggestion: $out" }
            val last = loc.endLine ?: first
            val case = "head=$head added=${addedLines.keys} existing=$existing got=$first..$last"

            assertTrue((first..last).all { it in addedLines }, case)
            // a trailing newline ends the snippet, as in AiMetadataTransformer, rather than adding an empty line;
            // a blank snippet counts as none, which only needs the range to be added lines
            if (existing.isNotBlank()) {
                assertEquals(existing.trimEnd('\n').lines().map(::squash), head.subList(first - 1, last).map(::squash), case)
            }
            val applied = head.subList(0, first - 1) + proposed.lines() + head.subList(last, head.size)
            assertTrue(applied.joinToString("\n").contains(proposed), case)
            assertEquals(head.subList(0, first - 1), applied.subList(0, first - 1), case)
            assertEquals(head.subList(last, head.size), applied.takeLast(head.size - last), case)
        }
    }

    private fun squash(line: String) = line.split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")
}
