package io.karandash.providers.common

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnifiedDiffTest {
    @Test
    fun splitsGitDiffPerFileAndStopsAtTheHunkEnd() {
        val raw =
            """
            From abc Mon Sep 17 00:00:00 2001
            Subject: [PATCH] add key

            diff --git a/src/A.kt b/src/A.kt
            index 1111111..2222222 100644
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -1,2 +1,3 @@
             package a
            +val key = 1
             // end
            diff --git a/img.png b/img.png
            Binary files a/img.png and b/img.png differ
            diff --git a/old.txt b/old.txt
            deleted file mode 100644
            --- a/old.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -gone
            --${' '}
            2.43.0
            """.trimIndent()

        val diffs = UnifiedDiff.splitByFile(raw)

        assertEquals(listOf("src/A.kt", "old.txt"), diffs.keys.toList())
        assertEquals("@@ -1,2 +1,3 @@\n package a\n+val key = 1\n // end", diffs["src/A.kt"])
        assertEquals("@@ -1 +0,0 @@\n-gone", diffs["old.txt"])
    }

    @Test
    fun betweenBuildsHunksWithContext() {
        val before = (1..10).joinToString("\n", postfix = "\n") { "line $it" }
        val after = before.replace("line 5\n", "line 5\nsecret = 1\n")

        assertEquals(
            "@@ -3,6 +3,7 @@\n line 3\n line 4\n line 5\n+secret = 1\n line 6\n line 7\n line 8",
            UnifiedDiff.between(before, after),
        )
    }

    @Test
    fun betweenHandlesAddedFilesAndEqualOrBinaryContent() {
        assertEquals("@@ -0,0 +1,2 @@\n+a\n+b", UnifiedDiff.between("", "a\nb\n"))
        assertNull(UnifiedDiff.between("same\n", "same\n"))
        assertNull(UnifiedDiff.between("a", "b\u0000"))
    }

    @Test
    fun betweenSplitsDistantChangesIntoSeparateHunks() {
        val before = (1..20).joinToString("\n") { "l$it" }
        val after = before.replace("l2\n", "L2\n").replace("l19\n", "L19\n")

        val diff = checkNotNull(UnifiedDiff.between(before, after))

        assertEquals(listOf("@@ -1,5 +1,5 @@", "@@ -16,5 +16,5 @@"), diff.lines().filter { it.startsWith("@@") })
    }

    @Test
    fun `fromContents diffs fetched contents and skips unchanged or missing entries`() =
        runTest {
            val diffs = UnifiedDiff.fromContents(
                entries = listOf("a.kt", "same.kt", "gone.kt"),
                path = { it },
                contents = {
                    when (it) {
                        "a.kt" -> "x\n" to "y\n"
                        "same.kt" -> "z\n" to "z\n"
                        else -> null
                    }
                },
            )

            assertEquals(listOf("a.kt"), diffs.keys.toList())
            assertEquals("@@ -1,1 +1,1 @@\n-x\n+y", diffs["a.kt"])
        }

    @Test
    fun `attach sets the diff and counts lines only for changes that report none`() {
        val diff = "@@ -1,2 +1,2 @@\n-a\n-b\n+c"
        val changes = listOf(
            FileChange("counted.kt", ChangeType.MODIFIED),
            FileChange("reported.kt", ChangeType.MODIFIED, additions = 9, deletions = 8),
            FileChange("untouched.kt", ChangeType.ADDED),
        )

        val result = UnifiedDiff.attach(changes, mapOf("counted.kt" to diff, "reported.kt" to diff))

        assertEquals(FileChange("counted.kt", ChangeType.MODIFIED, 1, 2, diff), result[0])
        assertEquals(FileChange("reported.kt", ChangeType.MODIFIED, 9, 8, diff), result[1])
        assertNull(result[2].diffContent)
    }

    @Test
    fun `a no newline marker after a finished hunk stays with it`() {
        val raw = listOf(
            "diff --git a/f.txt b/f.txt",
            "--- a/f.txt",
            "+++ b/f.txt",
            "@@ -1 +1 @@",
            "-old",
            "+new",
            "\\ No newline at end of file",
        ).joinToString("\n")

        assertEquals("@@ -1 +1 @@\n-old\n+new\n\\ No newline at end of file", UnifiedDiff.splitByFile(raw)["f.txt"])
    }

    @Test
    fun unquotesCStyleQuotedPaths() {
        val raw = listOf(
            "diff --git \"a/dir/a\\tb\\\"c.txt\" \"b/dir/a\\tb\\\"c.txt\"",
            "--- \"a/dir/a\\tb\\\"c.txt\"",
            "+++ \"b/dir/a\\tb\\\"c.txt\"",
            "@@ -1 +1 @@",
            "-x",
            "+y",
            "diff --git \"a/\\303\\274\\\\.txt\" \"b/\\303\\274\\\\.txt\"",
            "--- \"a/\\303\\274\\\\.txt\"",
            "+++ \"b/\\303\\274\\\\.txt\"",
            "@@ -1 +1 @@",
            "-p",
            "+q",
        ).joinToString("\n")

        val diffs = UnifiedDiff.splitByFile(raw)

        assertEquals(listOf("dir/a\tb\"c.txt", "\u00fc\\.txt"), diffs.keys.toList())
        assertEquals("@@ -1 +1 @@\n-x\n+y", diffs["dir/a\tb\"c.txt"])
    }
}
