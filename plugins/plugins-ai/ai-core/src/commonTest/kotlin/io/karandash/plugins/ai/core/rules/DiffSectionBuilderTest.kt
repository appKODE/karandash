package io.karandash.plugins.ai.core.rules

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.DiffMode
import io.karandash.plugins.ai.core.DiffPreprocessorOptions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DiffSectionBuilderTest {
    private val changes = listOf(
        FileChange(
            path = "src/Main.kt",
            changeType = ChangeType.MODIFIED,
            additions = 1,
            deletions = 0,
            diffContent = "+val x = 1",
            newContent = "package test\nval x = 1",
        ),
    )

    private fun hunk(n: Int) = "@@ -$n,1 +$n,1 @@\n-old $n\n+new line $n of the large file"

    @Test
    fun `a file over its cap is cut on whole hunks never mid-hunk`() {
        val diff = (1..3).joinToString("\n") { hunk(it) }
        val big = FileChange("src/Big.kt", ChangeType.MODIFIED, additions = 3, deletions = 3, diffContent = diff)

        val result = DiffSectionBuilder.build(
            listOf(big),
            maxDiffCharsTotal = 10_000,
            maxDiffCharsPerFile = hunk(1).length * 2 + 1,
            preprocessorOptions = DiffPreprocessorOptions(stripDeletedLines = true),
        )

        val kept = (1..3).filter { "+new line $it of the large file" in result }
        assertEquals(2, kept.size, result)
        (1..3).forEach { n -> assertEquals("+new line $n" in result, "@@ -$n,1 +$n,1 @@" in result, "hunk $n is whole or absent") }
        assertContains(result, "1 hunk omitted")
    }

    @Test
    fun `a file within its cap is sent whole`() {
        val diff = (1..3).joinToString("\n") { hunk(it) }
        val big = FileChange("src/Big.kt", ChangeType.MODIFIED, additions = 3, deletions = 3, diffContent = diff)

        val result = DiffSectionBuilder.build(listOf(big), maxDiffCharsTotal = 10_000, maxDiffCharsPerFile = diff.length)

        (1..3).forEach { n -> assertContains(result, "+new line $n of the large file") }
        assertFalse(result.contains("omitted") || result.contains("truncated"), result)
    }

    @Test
    fun `CHANGED mode uses diffContent and includes file header`() {
        val result = DiffSectionBuilder.build(
            changes = changes,
            maxDiffCharsTotal = 10000,
            maxDiffCharsPerFile = 5000,
            diffMode = DiffMode.CHANGED,
        )

        assertContains(result, "### src/Main.kt")
        assertContains(result, "+val x = 1")
        assertFalse(result.contains("package test"))
    }

    @Test
    fun `FULL mode uses newContent and includes file header`() {
        val result = DiffSectionBuilder.build(
            changes = changes,
            maxDiffCharsTotal = 10000,
            maxDiffCharsPerFile = 5000,
            diffMode = DiffMode.FULL,
        )

        assertContains(result, "### src/Main.kt")
        assertContains(result, "package test")
        assertContains(result, "val x = 1")
    }

    @Test
    fun `FULL mode falls back to diffContent when newContent is null`() {
        val changesNoNew = listOf(
            FileChange("src/Main.kt", ChangeType.MODIFIED, diffContent = "+val x = 1"),
        )
        val result = DiffSectionBuilder.build(
            changes = changesNoNew,
            maxDiffCharsTotal = 10000,
            maxDiffCharsPerFile = 5000,
            diffMode = DiffMode.FULL,
        )

        assertContains(result, "+val x = 1")
    }

    @Test
    fun `PATCH mode skips file path headers`() {
        val result = DiffSectionBuilder.build(
            changes = changes,
            maxDiffCharsTotal = 10000,
            maxDiffCharsPerFile = 5000,
            diffMode = DiffMode.PATCH,
        )

        assertFalse(result.contains("### src/Main.kt"))
        assertContains(result, "+val x = 1")
    }

    @Test
    fun `ADDED_ONLY mode keeps only added lines and hunk headers`() {
        val changesWithDiff = listOf(
            FileChange(
                path = "src/Main.kt",
                changeType = ChangeType.MODIFIED,
                additions = 2,
                deletions = 1,
                diffContent = "@@ -1,3 +1,4 @@\n context line\n-deleted line\n+added line\n+another added",
            ),
        )
        val result = DiffSectionBuilder.build(
            changes = changesWithDiff,
            maxDiffCharsTotal = 10000,
            maxDiffCharsPerFile = 5000,
            diffMode = DiffMode.ADDED_ONLY,
        )

        assertContains(result, "+added line")
        assertContains(result, "+another added")
        assertContains(result, "@@")
        assertFalse(result.contains("-deleted line"))
        // Context and deleted lines are removed entirely (not replaced with empty)
        // to preserve new-file line-number accuracy
        assertFalse(result.contains("context line"))
        // ADDED_ONLY includes file headers (only PATCH skips them)
        assertContains(result, "### src/Main.kt")
    }

    @Test
    fun `default diffMode is CHANGED`() {
        val result = DiffSectionBuilder.build(
            changes = changes,
            maxDiffCharsTotal = 10000,
            maxDiffCharsPerFile = 5000,
        )

        assertContains(result, "### src/Main.kt")
        assertContains(result, "+val x = 1")
    }

    // --- Line-number accuracy tests ---

    @Test
    fun `preprocessor stripDeletedLines preserves new-file line count in the diff section`() {
        // @@ -1,5 +1,4 @@ means 4 lines in the new file
        val changesWithDeletions = listOf(
            FileChange(
                path = "src/File.kt",
                changeType = ChangeType.MODIFIED,
                additions = 2,
                deletions = 1,
                diffContent = "@@ -1,5 +1,4 @@\n ctx1\n-deleted\n+added_a\n+added_b\n ctx2",
            ),
        )

        val result = DiffSectionBuilder.build(
            changes = changesWithDeletions,
            maxDiffCharsTotal = 10000,
            maxDiffCharsPerFile = 5000,
            preprocessorOptions = DiffPreprocessorOptions(
                skipDeletedFiles = false,
                skipPureRenames = false,
                stripDeletedLines = true,
                stripDiffMetadata = false,
                skipBinaryDiffs = false,
            ),
        )

        // Deleted line must be gone - not replaced with empty
        assertFalse(result.contains("-deleted"))
        // Hunk header and new-file lines remain
        assertContains(result, "@@")
        assertContains(result, "+added_a")
        assertContains(result, "+added_b")
        assertContains(result, " ctx1")
        assertContains(result, " ctx2")

        // Count lines in the diff portion (between header and trailing blank).
        // Should be: hunk header + 4 new-file lines = 5 lines total.
        val allLines = result.lines().dropWhile { !it.startsWith("@@") }
        val diffLines = allLines.takeWhile { it.isNotBlank() }
        assertEquals(5, diffLines.size, "stripped diff should have hunk header + exactly 4 new-file lines")
    }

    @Test
    fun `ADDED_ONLY mode removes context and deleted lines entirely`() {
        // @@ -1,4 +1,3 @@ means 3 new-file lines
        val changesMultiLine = listOf(
            FileChange(
                path = "src/App.kt",
                changeType = ChangeType.MODIFIED,
                diffContent = "@@ -1,4 +1,3 @@\n ctx\n-old\n+new\n bottom",
            ),
        )

        val result = DiffSectionBuilder.build(
            changes = changesMultiLine,
            maxDiffCharsTotal = 10000,
            maxDiffCharsPerFile = 5000,
            diffMode = DiffMode.ADDED_ONLY,
        )

        // Only hunk header and + lines should remain in the diff content
        assertFalse(result.contains(" ctx"), "context lines should be removed")
        assertFalse(result.contains("-old"), "deleted lines should be removed")
        assertFalse(result.contains(" bottom"), "context lines should be removed")
        assertContains(result, "@@")
        assertContains(result, "+new")
    }

    @Test
    fun `preprocessor does not affect file-level filtering for deleted files in the diff section`() {
        val changesWithDeleted = listOf(
            FileChange("deleted.kt", ChangeType.DELETED, diffContent = "-removed"),
            FileChange("kept.kt", ChangeType.MODIFIED, diffContent = "+kept line"),
        )

        val result = DiffSectionBuilder.build(
            changes = changesWithDeleted,
            maxDiffCharsTotal = 10000,
            maxDiffCharsPerFile = 5000,
            preprocessorOptions = DiffPreprocessorOptions(skipDeletedFiles = true),
        )

        assertFalse(result.contains("deleted.kt"), "deleted file should be filtered out")
        assertContains(result, "kept.kt")
        assertContains(result, "+kept line")
    }
}
