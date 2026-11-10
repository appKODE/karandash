package io.karandash.plugins.ai.core.rules

import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.DiffMode
import io.karandash.plugins.ai.core.DiffPreprocessor
import io.karandash.plugins.ai.core.DiffPreprocessorOptions
import io.karandash.plugins.ai.core.HunkAwareTruncator
import io.karandash.utils.mr.HunkParser
import io.karandash.utils.takeWhole

/** Builds the diff section of an AI prompt from the MR's file changes. */
object DiffSectionBuilder {
    /**
     * Build a diff section for an AI prompt by iterating over file changes and
     * truncating diffs to stay within configured size limits; a file over its cap keeps whole hunks.
     *
     * @param changes List of file changes from the merge request
     * @param maxDiffCharsTotal Maximum total characters across all diffs
     * @param maxDiffCharsPerFile Maximum characters per individual file diff
     * @param wrapInCodeBlock Whether to wrap each diff in ` ```diff ``` ` fences
     * @param diffMode Controls which content is used: FULL uses full file content, CHANGED/PATCH use diff hunks
     * @param preprocessorOptions Controls content-level diff preprocessing (strip deleted lines, metadata, etc.)
     * @return Formatted diff section string
     */
    fun build(
        changes: List<FileChange>,
        maxDiffCharsTotal: Int,
        maxDiffCharsPerFile: Int,
        wrapInCodeBlock: Boolean = false,
        diffMode: DiffMode = DiffMode.CHANGED,
        preprocessorOptions: DiffPreprocessorOptions = DiffPreprocessorOptions(),
    ): String =
        buildString {
            var totalDiffChars = 0
            val skippedFiles = mutableListOf<String>()

            val preprocessed = DiffPreprocessor.preprocess(changes, preprocessorOptions)

            for (change in preprocessed.changes) {
                val diff = diffContentFor(change, diffMode) ?: continue

                val available = maxDiffCharsTotal - totalDiffChars
                if (available <= 0) {
                    skippedFiles.add(change.path)
                    continue
                }

                val maxForFile = minOf(maxDiffCharsPerFile, available)
                val truncatedDiff = if (diff.length > maxForFile) cutToHunks(diff, maxForFile) else diff

                val entrySize = truncatedDiff.length
                if (totalDiffChars + entrySize > maxDiffCharsTotal && totalDiffChars > 0) {
                    skippedFiles.add(change.path)
                    continue
                }

                if (diffMode != DiffMode.PATCH) {
                    appendLine("### ${change.path}")
                }
                if (wrapInCodeBlock) {
                    appendLine("```diff")
                }
                appendLine(truncatedDiff)
                if (wrapInCodeBlock) {
                    appendLine("```")
                }
                appendLine()
                totalDiffChars += entrySize
            }

            if (skippedFiles.isNotEmpty()) {
                appendLine("(${skippedFiles.size} files omitted due to size limits: ${skippedFiles.joinToString(", ")})")
            }
        }

    /**
     * [diff] cut to [maxChars] on whole hunks; a diff whose first kept hunk alone is over [maxChars]
     * (or one with no hunk headers) is cut at the last line that fits.
     */
    private fun cutToHunks(
        diff: String,
        maxChars: Int,
    ): String {
        val byHunk = HunkAwareTruncator.truncate(diff, maxChars)
        if (byHunk.keptHunks > 0) return byHunk.output
        val cutoff = diff.lastIndexOf('\n', maxChars)
        return (if (cutoff > 0) diff.substring(0, cutoff) else diff.takeWhole(maxChars)) + "\n... (truncated)"
    }

    /**
     * The content of [change] sent to the model in [diffMode]: the new file content for
     * [DiffMode.FULL] (falling back to the diff), only `+` lines and hunk headers for
     * [DiffMode.ADDED_ONLY], the diff otherwise. Null when the change carries no content.
     */
    fun diffContentFor(
        change: FileChange,
        diffMode: DiffMode,
    ): String? =
        when (diffMode) {
            DiffMode.FULL -> change.newContent ?: change.diffContent
            DiffMode.CHANGED, DiffMode.PATCH -> change.diffContent
            DiffMode.ADDED_ONLY -> change.diffContent?.let { HunkParser.stripToAddedLines(it) }
        }
}
