package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.serialization.Serializable

/**
 * Preprocesses diffs before they are sent to LLMs, reducing token consumption
 * by filtering irrelevant files and stripping noise from diff content.
 *
 * Processing order: file-level filters first, then content-level transforms on survivors.
 */
object DiffPreprocessor {
    private const val BINARY_FILES_PREFIX = "Binary files"
    private const val GIT_BINARY_PATCH_PREFIX = "GIT binary patch"

    /** Extensions whose `package` / `import` lines a move rewrites without changing behavior. */
    private val JVM_EXTENSIONS = setOf("kt", "java", "scala", "groovy")

    /**
     * Applies all configured preprocessing steps to a list of file changes.
     *
     * @param changes Raw file changes from the merge request.
     * @param options Controls which preprocessing steps are active.
     * @return Preprocessed result with filtered/transformed changes and stats.
     */
    fun preprocess(
        changes: List<FileChange>,
        options: DiffPreprocessorOptions,
        logger: Logger = NoOpLogger,
    ): DiffPreprocessResult {
        var filtered = changes
        var deletedFilesRemoved = 0
        var pureRenamesRemoved = 0
        var binaryDiffsRemoved = 0

        if (options.skipDeletedFiles) {
            val before = filtered.size
            filtered = filtered.filter { change ->
                val isDeleted = change.changeType == ChangeType.DELETED
                if (isDeleted) {
                    logger.debug("  diff-preprocessor: skipped deleted file ${change.path}")
                }
                !isDeleted
            }
            deletedFilesRemoved = before - filtered.size
        }

        if (options.skipPureRenames) {
            val before = filtered.size
            filtered = filtered.filter { change ->
                val isRename = isPureRename(change)
                if (isRename) {
                    logger.debug("  diff-preprocessor: skipped pure rename ${change.path}")
                }
                !isRename
            }
            pureRenamesRemoved = before - filtered.size
        }

        if (options.skipBinaryDiffs) {
            val before = filtered.size
            filtered = filtered.filter { change ->
                val isBinary = isBinaryDiff(change)
                if (isBinary) {
                    logger.debug("  diff-preprocessor: skipped binary diff ${change.path}")
                }
                !isBinary
            }
            binaryDiffsRemoved = before - filtered.size
        }

        if (options.deletedFileStubLines > 0) {
            filtered = filtered.map { change ->
                if (change.changeType != ChangeType.DELETED) return@map change
                val diff = change.diffContent ?: return@map change
                val stub = deletedFileStub(diff, options.deletedFileStubLines)
                if (stub === diff) change else change.copy(diffContent = stub)
            }
        }

        val transformed = if (options.stripDeletedLines || options.stripDiffMetadata) {
            filtered.map { change ->
                val diff = change.diffContent ?: return@map change
                val processed = transformDiffContent(diff, options)
                if (processed === diff) change else change.copy(diffContent = processed)
            }
        } else {
            filtered
        }

        return DiffPreprocessResult(
            changes = transformed,
            stats = PreprocessStats(
                deletedFilesRemoved = deletedFilesRemoved,
                pureRenamesRemoved = pureRenamesRemoved,
                binaryDiffsRemoved = binaryDiffsRemoved,
            ),
        )
    }

    /** Why [preprocess] drops [change] whole under [options] (`deleted`, `pure rename`, `binary`), or null when it keeps it. */
    fun exclusionReason(
        change: FileChange,
        options: DiffPreprocessorOptions,
    ): String? =
        when {
            options.skipDeletedFiles && change.changeType == ChangeType.DELETED -> "deleted"
            options.skipPureRenames && isPureRename(change) -> change.oldPath?.let { "renamed from $it" } ?: "pure rename"
            options.skipBinaryDiffs && isBinaryDiff(change) -> "binary"
            else -> null
        }

    /**
     * Applies content-level transforms to a single diff string.
     *
     * Replaces deleted lines with empty lines (preserving line count for position references)
     * and removes diff metadata lines.
     */
    internal fun transformDiffContent(
        diff: String,
        options: DiffPreprocessorOptions,
    ): String {
        val lines = diff.split('\n')
        val result = StringBuilder(diff.length)
        var first = true

        for (line in lines) {
            if (options.stripDiffMetadata && isDiffMetadataLine(line)) {
                continue
            }

            if (options.stripDeletedLines && isDeletedLine(line)) {
                // Remove entirely - NOT replaced with empty lines.
                // In unified diffs, `-` lines belong to the old file only and do not
                // advance the new-file line counter. Keeping them as empty lines would
                // cause LLMs to miscount new-file positions, producing wrong line numbers
                // for inline review comments.
                continue
            }

            if (!first) result.append('\n')
            first = false
            result.append(line)
        }

        return result.toString()
    }

    /**
     * [diff] of a deleted file cut to its first [maxLines] `-` lines and a `... (K more deleted lines)` marker, or
     * [diff] itself when it is that short. Idempotent: a stub has at most [maxLines] `-` lines, so a second pass
     * (the MR preprocessor, then the diff builder) keeps it.
     */
    internal fun deletedFileStub(
        diff: String,
        maxLines: Int,
    ): String {
        val lines = diff.split('\n')
        val deleted = lines.count { isDeletedLine(it) }
        if (deleted <= maxLines) return diff
        val kept = mutableListOf<String>()
        var seen = 0
        for (line in lines) {
            if (isDeletedLine(line) && ++seen > maxLines) break
            kept += line
        }
        kept += "... (${deleted - maxLines} more deleted lines)"
        return kept.joinToString("\n")
    }

    /**
     * A rename with no diff, or one whose changed lines are all blank or, in a JVM source, `package` / `import`
     * lines (a move). The `---` / `+++` file headers sit before the first `@@`; after it they are changed lines.
     */
    private fun isPureRename(change: FileChange): Boolean {
        if (change.changeType != ChangeType.RENAMED) return false
        val diff = change.diffContent.orEmpty()
        if (diff.isBlank()) return change.additions + change.deletions == 0
        val jvm = change.path.substringAfterLast('.', "") in JVM_EXTENSIONS
        val lines = diff.lines()
        val body = lines.indexOfFirst { it.startsWith("@@") }.let { at ->
            if (at < 0) lines.dropWhile { it.startsWith("--- ") || it.startsWith("+++ ") } else lines.drop(at)
        }
        return body
            .filter { it.startsWith('+') || it.startsWith('-') }
            .all { line -> line.drop(1).trim().let { it.isEmpty() || (jvm && (it.startsWith("package ") || it.startsWith("import "))) } }
    }

    /** True when [change]'s diff is a git binary-file marker. */
    fun isBinaryDiff(change: FileChange): Boolean {
        val diff = change.diffContent ?: return false
        return diff.startsWith(BINARY_FILES_PREFIX) || diff.startsWith(GIT_BINARY_PATCH_PREFIX)
    }

    /**
     * A line starting with `-` but NOT `---` (which is diff metadata, handled separately).
     */
    private fun isDeletedLine(line: String): Boolean = line.startsWith('-') && !line.startsWith("---")

    private fun isDiffMetadataLine(line: String): Boolean =
        line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("\\ No newline")
}

/**
 * Result of diff preprocessing.
 *
 * @property changes Processed file changes.
 * @property stats Counts of what was filtered/transformed.
 */
@Serializable
data class DiffPreprocessResult(
    val changes: List<FileChange>,
    val stats: PreprocessStats,
)

/**
 * Statistics about what the preprocessor filtered.
 *
 * @property deletedFilesRemoved Number of entirely-deleted files removed.
 * @property pureRenamesRemoved Number of pure renames (no content change) removed.
 * @property binaryDiffsRemoved Number of binary file diffs removed.
 */
@Serializable
data class PreprocessStats(
    val deletedFilesRemoved: Int = 0,
    val pureRenamesRemoved: Int = 0,
    val binaryDiffsRemoved: Int = 0,
) {
    /** Total number of files removed by file-level filters. */
    val totalFilesRemoved: Int get() = deletedFilesRemoved + pureRenamesRemoved + binaryDiffsRemoved

    /**
     * Human-readable summary of preprocessing, or `null` if nothing was filtered.
     */
    fun summary(): String? {
        if (totalFilesRemoved == 0) return null
        val parts = mutableListOf<String>()
        if (deletedFilesRemoved > 0) parts.add("$deletedFilesRemoved deleted")
        if (pureRenamesRemoved > 0) parts.add("$pureRenamesRemoved pure renames")
        if (binaryDiffsRemoved > 0) parts.add("$binaryDiffsRemoved binary")
        return "Diff preprocessor: removed ${parts.joinToString(", ")} file(s)"
    }
}
