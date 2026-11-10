package io.karandash.plugins.ai.core

import kotlinx.serialization.Serializable

/**
 * Options controlling diff preprocessing before AI analysis.
 *
 * Token-saving filters are on by default except the two that hide removed code from the reviewer: deleted lines
 * and deleted files are kept (a deleted file as a [deletedFileStubLines]-line stub) so the model can see what an
 * MR takes away. Individual flags are set via the `size-policy.diff-preprocessor` config block.
 *
 * @param skipDeletedFiles   Remove entirely-deleted files.
 * @param skipPureRenames    Remove renamed files with zero content changes.
 * @param stripDeletedLines  Remove deleted (`-`) lines entirely to save tokens. Kept lines do not shift new-file
 *   line numbers: `-` lines never advance the new-file counter.
 * @param stripDiffMetadata  Remove `--- a/`, `+++ b/`, and `\ No newline at end of file` lines.
 * @param skipBinaryDiffs    Remove files whose diff indicates binary content.
 * @param deletedFileStubLines A kept deleted file's diff is cut to its first this many `-` lines plus a count of
 *   the rest; `0` keeps the whole diff.
 */
@Serializable
data class DiffPreprocessorOptions(
    val skipDeletedFiles: Boolean = DEFAULT_SKIP_DELETED_FILES,
    val skipPureRenames: Boolean = DEFAULT_SKIP_PURE_RENAMES,
    val stripDeletedLines: Boolean = DEFAULT_STRIP_DELETED_LINES,
    val stripDiffMetadata: Boolean = DEFAULT_STRIP_DIFF_METADATA,
    val skipBinaryDiffs: Boolean = DEFAULT_SKIP_BINARY_DIFFS,
    val deletedFileStubLines: Int = DEFAULT_DELETED_FILE_STUB_LINES,
) {
    companion object {
        const val DEFAULT_SKIP_DELETED_FILES = false
        const val DEFAULT_SKIP_PURE_RENAMES = true
        const val DEFAULT_STRIP_DELETED_LINES = false
        const val DEFAULT_STRIP_DIFF_METADATA = true
        const val DEFAULT_SKIP_BINARY_DIFFS = true
        const val DEFAULT_DELETED_FILE_STUB_LINES = 40
    }
}
