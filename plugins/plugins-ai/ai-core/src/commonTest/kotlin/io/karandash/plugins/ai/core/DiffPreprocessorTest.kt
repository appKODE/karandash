package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.sdk.Logger
import io.karandash.utils.mr.HunkParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffPreprocessorTest {
    private class CapturingLogger : Logger {
        val messages = mutableListOf<Pair<String, String>>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            messages.add("debug" to message)
        }

        override fun info(message: String) {
            messages.add("info" to message)
        }

        override fun warn(message: String) {
            messages.add("warn" to message)
        }

        override fun error(message: String) {
            messages.add("error" to message)
        }
    }

    // --- File-level filtering ---

    @Test
    fun `deleted files are removed when skipDeletedFiles is enabled`() {
        val changes = listOf(
            fileChange("added.kt", ChangeType.ADDED, diff = "+new file"),
            fileChange("deleted.kt", ChangeType.DELETED, diff = "-old file"),
            fileChange("modified.kt", ChangeType.MODIFIED, diff = "+change"),
        )
        val result = DiffPreprocessor.preprocess(changes, allDisabledOptions().copy(skipDeletedFiles = true))

        assertEquals(2, result.changes.size)
        assertEquals(listOf("added.kt", "modified.kt"), result.changes.map { it.path })
        assertEquals(1, result.stats.deletedFilesRemoved)
    }

    @Test
    fun `added and modified files are kept when skipDeletedFiles is enabled`() {
        val changes = listOf(
            fileChange("added.kt", ChangeType.ADDED, diff = "+new"),
            fileChange("modified.kt", ChangeType.MODIFIED, diff = "+mod"),
        )
        val result = DiffPreprocessor.preprocess(changes, allDisabledOptions().copy(skipDeletedFiles = true))

        assertEquals(2, result.changes.size)
        assertEquals(0, result.stats.deletedFilesRemoved)
    }

    @Test
    fun `pure renames are removed when skipPureRenames is enabled`() {
        val changes = listOf(
            fileChange("renamed-pure.kt", ChangeType.RENAMED, additions = 0, deletions = 0, diff = null),
            fileChange("renamed-pure-blank.kt", ChangeType.RENAMED, additions = 0, deletions = 0, diff = ""),
            fileChange("renamed-with-changes.kt", ChangeType.RENAMED, additions = 3, deletions = 1, diff = "+new line"),
        )
        val result = DiffPreprocessor.preprocess(changes, allDisabledOptions().copy(skipPureRenames = true))

        assertEquals(1, result.changes.size)
        assertEquals("renamed-with-changes.kt", result.changes.first().path)
        assertEquals(2, result.stats.pureRenamesRemoved)
    }

    @Test
    fun `renames with content changes are kept`() {
        val changes = listOf(
            fileChange("renamed.kt", ChangeType.RENAMED, additions = 5, deletions = 2, diff = "+stuff"),
        )
        val result = DiffPreprocessor.preprocess(changes, allDisabledOptions().copy(skipPureRenames = true))

        assertEquals(1, result.changes.size)
        assertEquals(0, result.stats.pureRenamesRemoved)
    }

    @Test
    fun `a move that only changes package and import lines collapses to renamed from the old path`() {
        val diff = "--- a/old/A.kt\n+++ b/new/A.kt\n@@ -1,3 +1,3 @@\n-package old\n+package new\n \n-import old.B\n+import new.B\n"
        val moved = fileChange("new/A.kt", ChangeType.RENAMED, additions = 2, deletions = 2, diff = diff).copy(oldPath = "old/A.kt")
        val edited = moved.copy(path = "new/C.kt", diffContent = "$diff+fun c() = 1\n", additions = 3)
        val options = allDisabledOptions().copy(skipPureRenames = true)

        val result = DiffPreprocessor.preprocess(listOf(moved, edited), options)

        assertEquals(listOf("new/C.kt"), result.changes.map { it.path })
        assertEquals("renamed from old/A.kt", DiffPreprocessor.exclusionReason(moved, options))
        assertNull(DiffPreprocessor.exclusionReason(edited, options))
    }

    @Test
    fun `a rename diff with file headers and no hunk is pure while a headerless added line is not`() {
        val options = allDisabledOptions().copy(skipPureRenames = true)
        val headersOnly = fileChange("new/A.kt", ChangeType.RENAMED, diff = "--- a/old/A.kt\n+++ b/new/A.kt\n")
        val edited = fileChange("new/B.kt", ChangeType.RENAMED, additions = 1, diff = "--- a/old/B.kt\n+++ b/new/B.kt\n+val b = 1\n")

        assertEquals("pure rename", DiffPreprocessor.exclusionReason(headersOnly, options))
        assertNull(DiffPreprocessor.exclusionReason(edited, options))
    }

    @Test
    fun `a rename that removes a line starting with two dashes is not pure`() {
        val diff = "--- a/old/q.sql\n+++ b/new/q.sql\n@@ -1,2 +1,1 @@\n--- drop the cache first\n select 1;\n"
        val options = allDisabledOptions().copy(skipPureRenames = true)

        assertNull(DiffPreprocessor.exclusionReason(fileChange("new/q.sql", ChangeType.RENAMED, deletions = 1, diff = diff), options))
    }

    @Test
    fun `import swaps outside JVM sources are not pure renames while a Kotlin package move still is`() {
        val python = "@@ -1,1 +1,1 @@\n-import os\n+import sys\n"
        val kotlin = "@@ -1,1 +1,1 @@\n-package old\n+package new\n"
        val options = allDisabledOptions().copy(skipPureRenames = true)

        val py = fileChange("new/m.py", ChangeType.RENAMED, additions = 1, deletions = 1, diff = python)
        val kt = fileChange("new/K.kt", ChangeType.RENAMED, additions = 1, deletions = 1, diff = kotlin)

        assertNull(DiffPreprocessor.exclusionReason(py, options))
        assertEquals(listOf("new/m.py"), DiffPreprocessor.preprocess(listOf(py, kt), options).changes.map { it.path })
    }

    @Test
    fun `binary diffs are detected and removed`() {
        val changes = listOf(
            fileChange("image.png", ChangeType.MODIFIED, diff = "Binary files a/image.png and b/image.png differ"),
            fileChange("archive.tar", ChangeType.ADDED, diff = "GIT binary patch\ndelta 123"),
            fileChange("code.kt", ChangeType.MODIFIED, diff = "+val x = 1"),
        )
        val result = DiffPreprocessor.preprocess(changes, allDisabledOptions().copy(skipBinaryDiffs = true))

        assertEquals(1, result.changes.size)
        assertEquals("code.kt", result.changes.first().path)
        assertEquals(2, result.stats.binaryDiffsRemoved)
    }

    @Test
    fun `all filters disabled results in passthrough`() {
        val changes = listOf(
            fileChange("deleted.kt", ChangeType.DELETED, diff = "-old"),
            fileChange("renamed.kt", ChangeType.RENAMED, additions = 0, deletions = 0, diff = ""),
            fileChange("binary.png", ChangeType.MODIFIED, diff = "Binary files differ"),
        )
        val result = DiffPreprocessor.preprocess(changes, allDisabledOptions())

        assertEquals(3, result.changes.size)
        assertEquals(0, result.stats.totalFilesRemoved)
    }

    @Test
    fun `empty input produces empty output`() {
        val result = DiffPreprocessor.preprocess(emptyList(), DiffPreprocessorOptions())

        assertTrue(result.changes.isEmpty())
        assertEquals(0, result.stats.totalFilesRemoved)
    }

    // --- Content-level transforms ---

    @Test
    fun `stripDeletedLines removes minus lines entirely but keeps triple-dash`() {
        val diff = "--- a/file.kt\n+++ b/file.kt\n@@ -1,5 +1,4 @@\n context line\n-deleted line\n+added line\n more context"

        val result = DiffPreprocessor.transformDiffContent(
            diff,
            allDisabledOptions().copy(stripDeletedLines = true),
        )

        assertTrue(result.contains("--- a/file.kt"), "triple-dash metadata should be kept")
        assertTrue(result.contains("+added line"))
        assertTrue(result.contains("context line"))
        assertTrue(result.contains("@@"))
        assertTrue(!result.contains("-deleted line"), "deleted line should be removed")

        // Deleted lines are removed entirely (not replaced with empty) to preserve
        // new-file line-number accuracy for inline review comments.
        // Original has 7 lines, result should have 6 (1 deleted line removed).
        assertEquals(6, result.split('\n').size, "deleted line should be removed, not replaced with empty")
    }

    @Test
    fun `stripDiffMetadata removes metadata lines`() {
        val diff = """
            |--- a/file.kt
            |+++ b/file.kt
            |@@ -1,3 +1,3 @@
            | context
            |-old
            |+new
            |\ No newline at end of file
        """.trimMargin()

        val result = DiffPreprocessor.transformDiffContent(
            diff,
            allDisabledOptions().copy(stripDiffMetadata = true),
        )

        assertTrue(!result.contains("--- a/file.kt"), "--- metadata should be removed")
        assertTrue(!result.contains("+++ b/file.kt"), "+++ metadata should be removed")
        assertTrue(!result.contains("No newline"), "no-newline marker should be removed")
        assertTrue(result.contains("@@ -1,3 +1,3 @@"), "hunk headers should be kept")
        assertTrue(result.contains("-old"), "deleted lines should be kept when stripDeletedLines is off")
        assertTrue(result.contains("+new"))
    }

    @Test
    fun `hunk headers and added lines are always kept`() {
        val diff = "@@ -1,2 +1,2 @@\n-old\n+new"
        val result = DiffPreprocessor.transformDiffContent(diff, DiffPreprocessorOptions())

        assertTrue(result.contains("@@"))
        assertTrue(result.contains("+new"))
    }

    // --- Integration ---

    @Test
    fun `full preprocess with all options applies all transforms`() {
        val changes = listOf(
            fileChange("deleted.kt", ChangeType.DELETED, diff = "-removed content"),
            fileChange("renamed.kt", ChangeType.RENAMED, additions = 0, deletions = 0, diff = ""),
            fileChange("binary.png", ChangeType.MODIFIED, diff = "Binary files a/b differ"),
            fileChange(
                "code.kt",
                ChangeType.MODIFIED,
                additions = 2,
                deletions = 1,
                diff = "--- a/code.kt\n+++ b/code.kt\n@@ -1,3 +1,4 @@\n context\n-old line\n+new line\n+extra line",
            ),
        )

        val result = DiffPreprocessor.preprocess(changes, strippingOptions())

        assertEquals(1, result.changes.size)
        assertEquals("code.kt", result.changes.first().path)
        assertEquals(1, result.stats.deletedFilesRemoved)
        assertEquals(1, result.stats.pureRenamesRemoved)
        assertEquals(1, result.stats.binaryDiffsRemoved)
        assertEquals(3, result.stats.totalFilesRemoved)

        val processedDiff = result.changes.first().diffContent!!
        assertTrue(!processedDiff.contains("--- a/code.kt"), "metadata stripped")
        assertTrue(!processedDiff.contains("-old line"), "deleted lines stripped")
        assertTrue(processedDiff.contains("+new line"))
        assertTrue(processedDiff.contains("+extra line"))
        assertTrue(processedDiff.contains("@@"))
    }

    @Test
    fun `stats summary returns null when nothing filtered`() {
        val changes = listOf(fileChange("code.kt", ChangeType.MODIFIED, diff = "+line"))
        val result = DiffPreprocessor.preprocess(changes, DiffPreprocessorOptions())

        assertNull(result.stats.summary())
    }

    @Test
    fun `stats summary describes what was removed`() {
        val changes = listOf(
            fileChange("del.kt", ChangeType.DELETED, diff = "-x"),
            fileChange("bin.png", ChangeType.MODIFIED, diff = "Binary files differ"),
        )
        val result = DiffPreprocessor.preprocess(changes, strippingOptions())

        val summary = result.stats.summary()!!
        assertTrue(summary.contains("1 deleted"))
        assertTrue(summary.contains("1 binary"))
    }

    // --- Line-number accuracy ---

    @Test
    fun `stripDeletedLines preserves new-file line count from hunk header`() {
        // Hunk header says +1,4 → 4 lines in new file (context + added lines)
        val diff = "@@ -1,5 +1,4 @@\n line1\n-deleted_a\n+added\n line3\n line4"

        val result = DiffPreprocessor.transformDiffContent(
            diff,
            allDisabledOptions().copy(stripDeletedLines = true),
        )

        val lines = result.split('\n')
        // Should be: hunk header + 4 new-file lines = 5 total
        assertEquals(5, lines.size)
        assertEquals("@@ -1,5 +1,4 @@", lines[0])
        assertEquals(" line1", lines[1])
        assertEquals("+added", lines[2])
        assertEquals(" line3", lines[3])
        assertEquals(" line4", lines[4])
    }

    @Test
    fun `stripDeletedLines with multiple deleted lines preserves line mapping`() {
        // New file: line 1 = context, line 2 = added_a, line 3 = added_b, line 4 = context
        val diff = "@@ -1,6 +1,4 @@\n context_top\n-del_1\n-del_2\n+added_a\n+added_b\n context_bottom"

        val result = DiffPreprocessor.transformDiffContent(
            diff,
            allDisabledOptions().copy(stripDeletedLines = true),
        )

        val lines = result.split('\n')
        // hunk header + 4 new-file lines
        assertEquals(5, lines.size)
        assertEquals("@@ -1,6 +1,4 @@", lines[0])
        assertEquals(" context_top", lines[1])
        assertEquals("+added_a", lines[2])
        assertEquals("+added_b", lines[3])
        assertEquals(" context_bottom", lines[4])
    }

    @Test
    fun `stripDeletedLines with multiple hunks preserves line positions in each`() {
        val diff = listOf(
            "@@ -1,4 +1,3 @@",
            " keep1",
            "-del1",
            "+add1",
            " keep2",
            "@@ -10,3 +9,3 @@",
            " keep3",
            "-del2",
            "+add2",
            " keep4",
        ).joinToString("\n")

        val result = DiffPreprocessor.transformDiffContent(
            diff,
            allDisabledOptions().copy(stripDeletedLines = true),
        )

        val lines = result.split('\n')
        // Hunk 1: header + 3 new-file lines = 4
        // Hunk 2: header + 3 new-file lines = 4
        // Total = 8
        assertEquals(8, lines.size)

        // Hunk 1 lines
        assertEquals("@@ -1,4 +1,3 @@", lines[0])
        assertEquals(" keep1", lines[1])
        assertEquals("+add1", lines[2])
        assertEquals(" keep2", lines[3])

        // Hunk 2 lines
        assertEquals("@@ -10,3 +9,3 @@", lines[4])
        assertEquals(" keep3", lines[5])
        assertEquals("+add2", lines[6])
        assertEquals(" keep4", lines[7])
    }

    @Test
    fun `combined stripDeletedLines and stripDiffMetadata preserves line accuracy`() {
        val diff = listOf(
            "--- a/file.kt",
            "+++ b/file.kt",
            "@@ -1,5 +1,3 @@",
            " context",
            "-old_a",
            "-old_b",
            "+new_line",
            " bottom",
        ).joinToString("\n")

        val result = DiffPreprocessor.transformDiffContent(diff, strippingOptions())

        val lines = result.split('\n')
        // metadata removed (2 lines), deleted lines removed (2 lines)
        // remaining: hunk header + 3 new-file lines = 4
        assertEquals(4, lines.size)
        assertEquals("@@ -1,5 +1,3 @@", lines[0])
        assertEquals(" context", lines[1])
        assertEquals("+new_line", lines[2])
        assertEquals(" bottom", lines[3])
    }

    @Test
    fun `preprocess does not mutate original FileChange diffContent`() {
        val originalDiff = "@@ -1,3 +1,2 @@\n context\n-deleted\n+added"
        val changes = listOf(
            fileChange("file.kt", ChangeType.MODIFIED, diff = originalDiff),
        )

        val result = DiffPreprocessor.preprocess(changes, strippingOptions())

        // Original FileChange in the input list is untouched
        assertEquals(originalDiff, changes.first().diffContent)

        // Preprocessed result has transformed content
        val processed = result.changes.first().diffContent!!
        assertTrue(!processed.contains("-deleted"))
        assertTrue(processed.contains("+added"))
    }

    @Test
    fun `file-level only preprocessing keeps diffContent unchanged for extractMaxLineFromDiff`() {
        // Simulates what AiRuleModule.applyDiffPreprocessorFileFilters does:
        // file-level filters only, no content transforms
        val diff = "@@ -1,5 +1,4 @@\n ctx\n-del\n+add\n ctx2\n ctx3"
        val changes = listOf(
            fileChange("keep.kt", ChangeType.MODIFIED, diff = diff),
            fileChange("removed.kt", ChangeType.DELETED, diff = "-gone"),
        )

        val fileOnlyOptions = DiffPreprocessorOptions(
            skipDeletedFiles = true,
            skipPureRenames = true,
            stripDeletedLines = false,
            stripDiffMetadata = false,
            skipBinaryDiffs = true,
        )
        val result = DiffPreprocessor.preprocess(changes, fileOnlyOptions)

        assertEquals(1, result.changes.size)
        // diffContent is IDENTICAL to original - hunk headers intact for line extraction
        assertEquals(diff, result.changes.first().diffContent)
    }

    // --- Logging tests ---

    @Test
    fun `logs per-file detail for deleted files`() {
        val logger = CapturingLogger()
        val changes = listOf(
            fileChange("deleted.kt", ChangeType.DELETED, diff = "-old"),
            fileChange("kept.kt", ChangeType.MODIFIED, diff = "+new"),
        )

        DiffPreprocessor.preprocess(changes, allDisabledOptions().copy(skipDeletedFiles = true), logger)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("skipped deleted file") && it.contains("deleted.kt") })
    }

    @Test
    fun `logs per-file detail for pure renames`() {
        val logger = CapturingLogger()
        val changes = listOf(
            fileChange("renamed.kt", ChangeType.RENAMED, additions = 0, deletions = 0, diff = ""),
        )

        DiffPreprocessor.preprocess(changes, allDisabledOptions().copy(skipPureRenames = true), logger)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("skipped pure rename") && it.contains("renamed.kt") })
    }

    @Test
    fun `logs per-file detail for binary diffs`() {
        val logger = CapturingLogger()
        val changes = listOf(
            fileChange("image.png", ChangeType.MODIFIED, diff = "Binary files a/b differ"),
        )

        DiffPreprocessor.preprocess(changes, allDisabledOptions().copy(skipBinaryDiffs = true), logger)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("skipped binary diff") && it.contains("image.png") })
    }

    @Test
    fun `no logging when nothing is filtered`() {
        val logger = CapturingLogger()
        val changes = listOf(fileChange("code.kt", ChangeType.MODIFIED, diff = "+line"))

        DiffPreprocessor.preprocess(changes, allDisabledOptions().copy(skipDeletedFiles = true), logger)

        assertTrue(logger.messages.isEmpty())
    }

    // --- Helpers ---

    @Test
    fun `a deleted file is kept by default as a stub of its first deleted lines`() {
        val body = (1..50).joinToString("\n") { "-line $it" }
        val diff = "--- a/gone.kt\n+++ /dev/null\n@@ -1,50 +0,0 @@\n$body"
        val changes = listOf(fileChange("gone.kt", ChangeType.DELETED, deletions = 50, diff = diff))

        val once = DiffPreprocessor.preprocess(changes, DiffPreprocessorOptions().copy(deletedFileStubLines = 3)).changes
        val stub = checkNotNull(once.single().diffContent)
        assertEquals("@@ -1,50 +0,0 @@\n-line 1\n-line 2\n-line 3\n... (47 more deleted lines)", stub)

        val twice = DiffPreprocessor.preprocess(once, DiffPreprocessorOptions().copy(deletedFileStubLines = 3)).changes
        assertEquals(stub, twice.single().diffContent)
    }

    @Test
    fun `kept deleted lines leave added-line numbers unchanged`() {
        val diff = "@@ -10,4 +10,3 @@\n ctx\n-old one\n-old two\n+new one\n ctx2\n+new two"
        val kept = DiffPreprocessor.preprocess(listOf(fileChange("a.kt", ChangeType.MODIFIED, diff = diff)), DiffPreprocessorOptions())
        val keptDiff = checkNotNull(kept.changes.single().diffContent)

        val annotated = HunkParser.annotateAddedLinesWithNumbers(keptDiff)
        assertTrue(annotated.contains("-old one\n-old two\nL11(+) new one"), annotated)
        assertTrue(annotated.contains("L13(+) new two"), annotated)
        assertEquals(setOf(11, 13), HunkParser.extractAddedLineNumbers(keptDiff))
    }

    /** Deleted files skipped and deleted lines stripped. */
    private fun strippingOptions() = DiffPreprocessorOptions(skipDeletedFiles = true, stripDeletedLines = true)

    private fun fileChange(
        path: String,
        changeType: ChangeType,
        additions: Int = 0,
        deletions: Int = 0,
        diff: String? = null,
    ) = FileChange(
        path = path,
        changeType = changeType,
        additions = additions,
        deletions = deletions,
        diffContent = diff,
    )

    private fun allDisabledOptions() =
        DiffPreprocessorOptions(
            skipDeletedFiles = false,
            skipPureRenames = false,
            stripDeletedLines = false,
            stripDiffMetadata = false,
            skipBinaryDiffs = false,
        )
}
