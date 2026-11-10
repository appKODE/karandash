package io.karandash.utils.mr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HunkParserTest {
    @Test
    fun `parseHunks handles empty and null input`() {
        assertTrue(HunkParser.parseHunks(null).isEmpty())
        assertTrue(HunkParser.parseHunks("").isEmpty())
        assertTrue(HunkParser.parseHunks("   \n  \n").isEmpty())
    }

    @Test
    fun `parseHunks treats binary marker as empty`() {
        val diff = "Binary files a/logo.png and b/logo.png differ\n"
        assertTrue(HunkParser.parseHunks(diff).isEmpty())
    }

    @Test
    fun `parseHunks treats submodule marker as empty`() {
        val diff = """
            -Subproject commit deadbeef
            +Subproject commit cafebabe
        """.trimIndent()
        assertTrue(HunkParser.parseHunks(diff).isEmpty())
    }

    @Test
    fun `parseHunks extracts single hunk with added and removed lines`() {
        val diff = """
            @@ -1,3 +1,4 @@
             line1
            -old
            +new1
            +new2
             line3
        """.trimIndent()
        val hunks = HunkParser.parseHunks(diff)
        assertEquals(1, hunks.size)
        val hunk = hunks.single()
        assertEquals("@@ -1,3 +1,4 @@", hunk.header)
        assertEquals(2, hunk.addedLines)
        assertEquals(1, hunk.deletedLines)
        assertEquals(2, hunk.contextLines)
        assertEquals(1, hunk.newStartLine)
        assertEquals(4, hunk.newLineCount)
    }

    @Test
    fun `parseHunks extracts multiple hunks`() {
        val diff = """
            @@ -1,2 +1,2 @@
             context
            +added1
            @@ -10,2 +10,3 @@
             context
            +added2
            +added3
        """.trimIndent()
        val hunks = HunkParser.parseHunks(diff)
        assertEquals(2, hunks.size)
        assertEquals(1, hunks[0].addedLines)
        assertEquals(2, hunks[1].addedLines)
        assertEquals(10, hunks[1].newStartLine)
    }

    @Test
    fun `parseHunks ignores file-level headers before first hunk`() {
        val diff = """
            diff --git a/src/Foo.kt b/src/Foo.kt
            index abc..def 100644
            --- a/src/Foo.kt
            +++ b/src/Foo.kt
            @@ -1 +1,2 @@
             existing
            +added
        """.trimIndent()
        val hunks = HunkParser.parseHunks(diff)
        assertEquals(1, hunks.size)
        assertEquals(1, hunks.single().addedLines)
    }

    @Test
    fun `parseHunks accepts single-line header without count`() {
        val diff = """
            @@ -5 +5 @@
            -old
            +new
        """.trimIndent()
        val hunks = HunkParser.parseHunks(diff)
        assertEquals(1, hunks.size)
        val hunk = hunks.single()
        assertEquals(1, hunk.newLineCount) // Default when count not specified
        assertEquals(5, hunk.newStartLine)
    }

    @Test
    fun `parseHunks tolerates no-newline pseudo-line`() {
        val diff = """
            @@ -1,2 +1,2 @@
            -old
            +new
            \ No newline at end of file
        """.trimIndent()
        val hunks = HunkParser.parseHunks(diff)
        assertEquals(1, hunks.single().addedLines)
    }

    @Test
    fun `extractAddedLineNumbers matches legacy behaviour`() {
        val diff = """
            @@ -1,3 +1,5 @@
             line1
            +added1
            +added2
             line2
            +added3
        """.trimIndent()
        assertEquals(setOf(2, 3, 5), HunkParser.extractAddedLineNumbers(diff))
    }

    @Test
    fun `extractAddedLineNumbers returns empty for null`() {
        assertTrue(HunkParser.extractAddedLineNumbers(null).isEmpty())
    }

    @Test
    fun `extractAddedLineNumbers skips pseudo-lines`() {
        val diff = """
            @@ -1,1 +1,1 @@
            -old
            +new
            \ No newline at end of file
        """.trimIndent()
        assertEquals(setOf(1), HunkParser.extractAddedLineNumbers(diff))
    }

    @Test
    fun `stripToAddedLines keeps headers and plus lines only`() {
        val diff = """
            @@ -1,4 +1,4 @@
             context
            -removed
            +added
             context2
        """.trimIndent()
        val stripped = HunkParser.stripToAddedLines(diff)
        val expected = """
            @@ -1,4 +1,4 @@
            +added
        """.trimIndent()
        assertEquals(expected, stripped)
    }

    @Test
    fun `annotateAddedLinesWithNumbers tags added lines with L n plus`() {
        val diff = """
            @@ -1,4 +1,5 @@
             ctx1
            -removed
            +added_at_2
            +added_at_3
             ctx2
        """.trimIndent()
        val annotated = HunkParser.annotateAddedLinesWithNumbers(diff)
        val expected = """
            @@ -1,4 +1,5 @@
             ctx1
            -removed
            L2(+) added_at_2
            L3(+) added_at_3
             ctx2
        """.trimIndent()
        assertEquals(expected, annotated)
    }

    @Test
    fun `annotateAddedLinesWithNumbers preserves blank input`() {
        assertEquals("", HunkParser.annotateAddedLinesWithNumbers(""))
    }

    @Test
    fun `annotateAddedLinesWithNumbers resets counter on each hunk`() {
        val diff = """
            @@ -1,2 +1,2 @@
            +one
             ctx
            @@ -10,2 +10,2 @@
            +ten
             ctx
        """.trimIndent()
        val annotated = HunkParser.annotateAddedLinesWithNumbers(diff)
        assertEquals(
            """
                @@ -1,2 +1,2 @@
                L1(+) one
                 ctx
                @@ -10,2 +10,2 @@
                L10(+) ten
                 ctx
            """.trimIndent(),
            annotated,
        )
    }

    @Test
    fun `annotateAddedLinesWithNumbers handles multi-file diff with file headers`() {
        val diff = """
            --- a/src/Foo.kt
            +++ b/src/Foo.kt
            @@ -1,2 +1,2 @@
             ctx
            +first
            --- a/src/Bar.kt
            +++ b/src/Bar.kt
            @@ -50,1 +50,2 @@
             ctx
            +second
        """.trimIndent()
        val annotated = HunkParser.annotateAddedLinesWithNumbers(diff)
        // +++ lines are passed through unchanged; file headers reset nothing, but the
        // next @@ header resets the counter, so line numbers are correct per file.
        assertEquals(
            """
                --- a/src/Foo.kt
                +++ b/src/Foo.kt
                @@ -1,2 +1,2 @@
                 ctx
                L2(+) first
                --- a/src/Bar.kt
                +++ b/src/Bar.kt
                @@ -50,1 +50,2 @@
                 ctx
                L51(+) second
            """.trimIndent(),
            annotated,
        )
    }

    @Test
    fun `snapToAddedLine returns exact match immediately`() {
        assertEquals(5, HunkParser.snapToAddedLine(5, setOf(3, 5, 7)))
    }

    @Test
    fun `snapToAddedLine prefers forward on tie`() {
        assertEquals(6, HunkParser.snapToAddedLine(5, setOf(4, 6)))
    }

    @Test
    fun `snapToAddedLine returns null when out of range`() {
        assertNull(HunkParser.snapToAddedLine(100, setOf(1, 2, 3), maxSnap = 5))
    }

    @Test
    fun `snapToAddedLine handles empty set`() {
        assertNull(HunkParser.snapToAddedLine(5, emptySet()))
    }

    @Test
    fun `totalChars counts header plus body lines`() {
        val diff = "@@ -1 +1,2 @@\n old\n+new"
        val hunk = HunkParser.parseHunks(diff).single()
        // Header + '\n' + each body line + '\n'
        val expected = "@@ -1 +1,2 @@".length + 1 + (" old".length + 1) + ("+new".length + 1)
        assertEquals(expected, hunk.totalChars)
    }

    @Test
    fun `addedDensity reflects ratio of added lines`() {
        val diff = """
            @@ -1,4 +1,4 @@
             ctx
            -removed
            +added
             ctx2
        """.trimIndent()
        val hunk = HunkParser.parseHunks(diff).single()
        // 1 added, 1 deleted, 2 context -> 1/4 = 0.25
        assertEquals(0.25, hunk.addedDensity, absoluteTolerance = 0.001)
    }
}

/** Approximate-equals helper local to this test file. */
private fun assertEquals(
    expected: Double,
    actual: Double,
    absoluteTolerance: Double
) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "expected $expected ±$absoluteTolerance but was $actual",
    )
}
