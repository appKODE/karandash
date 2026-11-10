package io.karandash.plugins.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HunkAwareTruncatorTest {
    @Test
    fun `null and blank input return empty`() {
        val none = HunkAwareTruncator.truncate(null, budget = 1_000)
        assertEquals(HunkAwareTruncator.TruncationResult.EMPTY, none)
        val blank = HunkAwareTruncator.truncate("   ", budget = 1_000)
        assertEquals(HunkAwareTruncator.TruncationResult.EMPTY, blank)
    }

    @Test
    fun `zero budget returns empty`() {
        val result = HunkAwareTruncator.truncate("@@ -1 +1 @@\n+foo", budget = 0)
        assertEquals(HunkAwareTruncator.TruncationResult.EMPTY, result)
    }

    @Test
    fun `binary marker returned as placeholder`() {
        val result = HunkAwareTruncator.truncate(
            "Binary files a/logo.png and b/logo.png differ\n",
            budget = 1_000,
        )
        assertEquals("(binary file)", result.output)
        assertEquals(0, result.keptHunks)
        assertEquals(0, result.omittedHunks)
    }

    @Test
    fun `submodule marker returned as placeholder`() {
        val result = HunkAwareTruncator.truncate(
            "-Subproject commit deadbeef\n+Subproject commit cafebabe\n",
            budget = 1_000,
        )
        assertEquals("(submodule change)", result.output)
    }

    @Test
    fun `diff without headers treated as raw block under budget`() {
        val diff = "malformed but non-empty content\n"
        val result = HunkAwareTruncator.truncate(diff, budget = 1_000)
        assertTrue(result.output.isNotEmpty())
        assertEquals(0, result.keptHunks)
    }

    @Test
    fun `diff without headers over budget truncates with marker`() {
        val diff = buildString { repeat(100) { appendLine("line $it") } }
        val result = HunkAwareTruncator.truncate(diff, budget = 50)
        assertTrue(result.output.endsWith("... (truncated)"))
    }

    @Test
    fun `single hunk within budget preserved verbatim`() {
        val diff = """
            @@ -1,3 +1,3 @@
             ctx
            -old
            +new
        """.trimIndent()
        val result = HunkAwareTruncator.truncate(diff, budget = 1_000)
        assertEquals(1, result.keptHunks)
        assertEquals(0, result.omittedHunks)
        assertTrue(result.output.contains("+new"))
    }

    @Test
    fun `budget fits only highest-density hunk`() {
        val diff = """
            @@ -1,4 +1,4 @@
             ctx1
             ctx2
             ctx3
            -old
            +new
            @@ -20,1 +20,4 @@
            +new1
            +new2
            +new3
            +new4
        """.trimIndent()
        // Hunk 2 has 4/4 = 1.0 density; hunk 1 has 1/5 = 0.2 density.
        val result = HunkAwareTruncator.truncate(diff, budget = 80)
        assertEquals(1, result.keptHunks)
        assertEquals(1, result.omittedHunks)
        assertTrue(result.output.contains("+new4"))
        assertTrue(result.output.contains("1 hunk omitted"))
    }

    @Test
    fun `mostly removed hunk with a removed guard outranks an added hunk`() {
        val diff = """
            @@ -1,4 +1,1 @@
             val user = load()
            -if (user == null) return
            -check(user.active)
            -audit(user)
            @@ -20,1 +17,4 @@
            +val a = 1
            +val b = 2
            +val c = 3
            +val d = 4
        """.trimIndent()
        val result = HunkAwareTruncator.truncate(diff, budget = 100)
        assertEquals(1, result.keptHunks)
        assertTrue(result.output.contains("-if (user == null) return"))
    }

    @Test
    fun `multiple hunks fit fully with no omission marker`() {
        val diff = """
            @@ -1,1 +1,1 @@
            +a
            @@ -2,1 +2,1 @@
            +b
        """.trimIndent()
        val result = HunkAwareTruncator.truncate(diff, budget = 1_000)
        assertEquals(2, result.keptHunks)
        assertEquals(0, result.omittedHunks)
        assertTrue(!result.output.contains("omitted"))
    }

    @Test
    fun `kept hunks restored to original order after scoring`() {
        val diff = """
            @@ -1,5 +1,5 @@
             ctx1
             ctx2
             ctx3
             ctx4
            +tiny
            @@ -20,1 +20,3 @@
            +fat1
            +fat2
            +fat3
            @@ -40,1 +40,2 @@
            +mid1
            +mid2
        """.trimIndent()
        val result = HunkAwareTruncator.truncate(diff, budget = 1_000)
        assertEquals(3, result.keptHunks)
        // Even though hunk 2 had highest density, output must restore 1 -> 2 -> 3.
        val firstIdx = result.output.indexOf("+tiny")
        val secondIdx = result.output.indexOf("+fat1")
        val thirdIdx = result.output.indexOf("+mid1")
        assertTrue(firstIdx >= 0 && secondIdx > firstIdx && thirdIdx > secondIdx)
    }

    @Test
    fun `omission marker uses singular for one hunk`() {
        val diff = """
            @@ -1,1 +1,1 @@
            +small
            @@ -10,3 +10,3 @@
             big1
             big2
             big3
        """.trimIndent()
        val result = HunkAwareTruncator.truncate(diff, budget = 30)
        assertTrue(result.output.contains("1 hunk omitted"))
        assertTrue(!result.output.contains("1 hunks omitted"))
    }

    @Test
    fun `preserveBoundaries false keeps original order and stops at budget`() {
        val diff = """
            @@ -1,3 +1,3 @@
             ctx1
             ctx2
            +add1
            @@ -20,1 +20,4 @@
            +fat1
            +fat2
            +fat3
            +fat4
        """.trimIndent()
        val result = HunkAwareTruncator.truncate(diff, budget = 60, preserveBoundaries = false)
        assertTrue(result.output.contains("+add1")) // First hunk kept
    }

    @Test
    fun `contextLinesToKeep strips excess leading and trailing context`() {
        val diff = """
            @@ -1,8 +1,8 @@
             ctx1
             ctx2
             ctx3
            +added
             ctx4
             ctx5
             ctx6
        """.trimIndent()
        val result = HunkAwareTruncator.truncate(diff, budget = 1_000, contextLinesToKeep = 1)
        // Only 1 leading and 1 trailing context kept; the +added line is preserved.
        val lines = result.output.lines()
        val contextLines = lines.filter { it.startsWith(" ") }
        assertTrue(contextLines.size <= 2, "expected ≤ 2 context lines, got: $contextLines")
        assertTrue(result.output.contains("+added"))
    }

    @Test
    fun `contextLinesToKeep zero strips all context`() {
        val diff = """
            @@ -1,3 +1,3 @@
             ctx1
            +added
             ctx2
        """.trimIndent()
        val result = HunkAwareTruncator.truncate(diff, budget = 1_000, contextLinesToKeep = 0)
        val lines = result.output.lines()
        assertTrue(lines.none { it.startsWith(" ") })
        assertTrue(result.output.contains("+added"))
    }
}
