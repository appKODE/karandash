package io.karandash.utils.finding

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AnchorFingerprintTest {
    @Test
    fun `whitespace differences in the anchor line give the same fingerprint`() {
        val a = AnchorFingerprint.generate("ai-review-comments", "src/A.kt", "    val x =  foo( 1 )")
        val b = AnchorFingerprint.generate("ai-review-comments", "src/A.kt", "val x = foo( 1 )\t")
        assertEquals(a, b)
        assertEquals(16, a.length)
    }

    @Test
    fun `different rule path or line text give different fingerprints`() {
        val base = AnchorFingerprint.generate("r", "a.kt", "val x = 1")
        assertNotEquals(base, AnchorFingerprint.generate("r2", "a.kt", "val x = 1"))
        assertNotEquals(base, AnchorFingerprint.generate("r", "b.kt", "val x = 1"))
        assertNotEquals(base, AnchorFingerprint.generate("r", "a.kt", "val x = 2"))
    }

    @Test
    fun `fingerprint is stable when the anchor line shifts in the file`() {
        val random = Random(SEED)
        repeat(CASES) {
            val lines = List(random.nextInt(5, 40)) { i -> "line$i = ${random.nextInt()}" }
            val anchorIndex = random.nextInt(lines.size)
            val anchor = lines[anchorIndex]
            val inserted = List(random.nextInt(1, 10)) { "inserted ${random.nextInt()}" }
            val shifted = lines.take(anchorIndex) + inserted + lines.drop(anchorIndex)
            val shiftedIndex = anchorIndex + inserted.size
            val reindented = "  ".repeat(random.nextInt(4)) + shifted[shiftedIndex]
            assertEquals(
                AnchorFingerprint.generate("rule", "p.kt", anchor),
                AnchorFingerprint.generate("rule", "p.kt", reindented),
                "case $it: anchor moved from ${anchorIndex + 1} to ${shiftedIndex + 1}",
            )
        }
    }

    @Test
    fun `stamp stores the fingerprint of the anchored added line`() {
        val change = FileChange(
            path = "a.kt",
            changeType = ChangeType.MODIFIED,
            diffContent = "@@ -1,1 +1,2 @@\n ctx\n+  val added = 1",
        )
        val onAdded = Finding(Severity.WARNING, "m", "r", Location.FileRef("a.kt", 2))
        val unknownPath = Finding(Severity.WARNING, "m", "r", Location.FileRef("b.kt", 2))
        val fileLevel = Finding(Severity.WARNING, "m", "r", Location.FileRef("a.kt"))

        val (stamped, noPath, noLine) = AnchorFingerprint.stamp(listOf(onAdded, unknownPath, fileLevel), listOf(change))

        assertEquals(
            AnchorFingerprint.generate("r", "a.kt", "val added = 1"),
            stamped.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT],
        )
        assertNull(noPath.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT])
        assertNull(noLine.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT])
    }

    @Test
    fun `identical added lines get distinct fingerprints`() {
        val change = FileChange(
            path = "a.kt",
            changeType = ChangeType.MODIFIED,
            diffContent = "@@ -1,1 +1,4 @@\n ctx\n+    return null\n+val y = 2\n+  return null",
        )
        val first = Finding(Severity.WARNING, "m", "r", Location.FileRef("a.kt", 2))
        val second = Finding(Severity.WARNING, "m", "r", Location.FileRef("a.kt", 4))

        val (a, b) = AnchorFingerprint.stamp(listOf(first, second), listOf(change))

        val fpvA = a.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT]
        assertEquals(AnchorFingerprint.generate("r", "a.kt", "return null"), fpvA)
        assertNotEquals(fpvA, b.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT])
        val fpvB = b.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT]
        assertEquals(AnchorFingerprint.generate("r", "a.kt", "return null", occurrence = 1), fpvB)
    }

    @Test
    fun `identical line inserted above shifts occurrence of later duplicate`() {
        val before = FileChange(path = "a.kt", changeType = ChangeType.MODIFIED, diffContent = "@@ -1,1 +1,2 @@\n ctx\n+return null")
        val after = FileChange(
            path = "a.kt",
            changeType = ChangeType.MODIFIED,
            diffContent = "@@ -1,1 +1,3 @@\n+return null\n ctx\n+return null",
        )
        val original = AnchorFingerprint.generate("r", "a.kt", "return null")

        assertEquals(mapOf(original to 2), AnchorFingerprint.anchoredLines("r", before))
        // Known limitation: the inserted copy takes occurrence 0, so the original line now hashes as occurrence 1
        assertEquals(
            mapOf(original to 1, AnchorFingerprint.generate("r", "a.kt", "return null", 1) to 3),
            AnchorFingerprint.anchoredLines("r", after),
        )
    }

    @Test
    fun `blank and brace-only lines are not stamped while a code line is`() {
        val change = FileChange(
            path = "a.kt",
            changeType = ChangeType.MODIFIED,
            diffContent = "@@ -1,1 +1,4 @@\n ctx\n+   \n+  }\n+val z = 3",
        )
        val findings = listOf(2, 3, 4).map { Finding(Severity.WARNING, "m", "r", Location.FileRef("a.kt", it)) }

        val (blank, brace, code) = AnchorFingerprint.stamp(findings, listOf(change))

        assertNull(blank.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT])
        assertNull(brace.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT])
        assertEquals(AnchorFingerprint.generate("r", "a.kt", "val z = 3"), code.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT])
    }

    @Test
    fun `dot-slash and backslash paths stamp the same fingerprint as the canonical path`() {
        val change = FileChange(
            path = "./src/A.kt",
            changeType = ChangeType.RENAMED,
            diffContent = "@@ -1,1 +1,2 @@\n ctx\n+val w = 4",
        )
        val findings = listOf("src/A.kt", "./src/A.kt", "src\\A.kt").map { Finding(Severity.WARNING, "m", "r", Location.FileRef(it, 2)) }

        val stamped = AnchorFingerprint.stamp(findings, listOf(change))

        val expected = AnchorFingerprint.generate("r", "src/A.kt", "val w = 4")
        assertEquals(listOf(expected, expected, expected), stamped.map { it.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT] })
    }

    @Test
    fun `stamp reads a non-added anchor line from the new content`() {
        val change = FileChange(
            path = "a.kt",
            changeType = ChangeType.MODIFIED,
            diffContent = "@@ -2,1 +2,2 @@\n ctx\n+val added = 1",
            newContent = "val first = 0\nctx\nval added = 1",
        )
        val (stamped) = AnchorFingerprint.stamp(listOf(Finding(Severity.WARNING, "m", "r", Location.FileRef("a.kt", 1))), listOf(change))

        assertEquals(AnchorFingerprint.generate("r", "a.kt", "val first = 0"), stamped.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT])
    }

    @Test
    fun `anchoredLines maps each added code line fingerprint to its line`() {
        val change = FileChange(path = "a.kt", changeType = ChangeType.MODIFIED, diffContent = "@@ -1,1 +1,3 @@\n ctx\n+val v = 1\n+}")

        assertEquals(mapOf(AnchorFingerprint.generate("r", "a.kt", "val v = 1") to 2), AnchorFingerprint.anchoredLines("r", change))
    }

    private companion object {
        const val SEED = 1411
        const val CASES = 200
    }

    @Test
    fun `fingerprintsIn holds every occurrence of every anchorable head line`() {
        val content = "fun f() {\n    return null\n}\n  return  null\n\nval x = 1\n"

        val set = AnchorFingerprint.fingerprintsIn("r", "./a.kt", content)

        assertEquals(
            setOf(
                AnchorFingerprint.generate("r", "a.kt", "fun f() {"),
                AnchorFingerprint.generate("r", "a.kt", "return null", 0),
                AnchorFingerprint.generate("r", "a.kt", "return null", 1),
                AnchorFingerprint.generate("r", "a.kt", "val x = 1"),
            ),
            set,
        )
    }
}
