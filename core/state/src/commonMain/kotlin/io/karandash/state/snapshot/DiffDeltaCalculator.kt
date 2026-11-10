package io.karandash.state.snapshot

import io.karandash.crypto.sha256First64Bits
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import kotlinx.serialization.Serializable

/**
 * Calculates the delta between a previous review snapshot and the current MR state.
 *
 * A snapshot records the hash of each file's diff content at the time of review,
 * plus a metadata hash covering title, description, and commit messages.
 * On subsequent validation, only files with changed or new diffs are included,
 * saving AI tokens on unchanged code.
 */
object DiffDeltaCalculator {
    /** Synthetic key for MR-level metadata in the snapshot. */
    const val MR_METADATA_KEY = "__mr-metadata__"

    /** Synthetic key for the resolved config hash in the snapshot. */
    const val CONFIG_KEY = "__config__"

    /**
     * Creates a snapshot of the current MR's file changes and metadata.
     *
     * @param mr The merge request to snapshot.
     * @param configHash Optional hash of the resolved config. When provided, stored under [CONFIG_KEY]
     *   so that config changes invalidate the cache on the next run.
     * @return Map of file path to diff content hash, plus a metadata hash entry and optional config hash.
     */
    fun snapshot(
        mr: MergeRequest,
        configHash: Long? = null
    ): Map<String, Long> {
        val fileSnapshot = mr.changes.associate { change ->
            change.path to (change.diffContent?.let { sha256First64Bits(it) } ?: 0L)
        }
        val result = fileSnapshot + (MR_METADATA_KEY to computeMetadataHash(mr))
        return if (configHash != null) result + (CONFIG_KEY to configHash) else result
    }

    /**
     * Filters an MR to include only files that are new or changed since [previousSnapshot].
     *
     * File-level delta is computed independently from metadata changes:
     * - A config change triggers full invalidation (rules/models may have changed).
     * - A metadata change sets [DeltaResult.metadataChanged] but does not nuke file-level findings.
     * - File comparisons are always done on their own hashes regardless of metadata.
     *
     * @param mr The current merge request.
     * @param previousSnapshot Snapshot from a previous review run (file path to hash).
     * @param currentConfigHash Optional current config hash. When provided and differs from
     *   the stored config hash, full invalidation is triggered.
     * @return A [DeltaResult] containing only delta file changes, plus a list of unchanged file paths.
     */
    fun delta(
        mr: MergeRequest,
        previousSnapshot: Map<String, Long>,
        currentConfigHash: Long? = null,
    ): DeltaResult {
        if (previousSnapshot.isEmpty()) {
            return DeltaResult(
                mr = mr,
                unchangedFiles = emptyList(),
                metadataChanged = true,
                configChanged = true,
            )
        }

        val previousMetaHash = previousSnapshot[MR_METADATA_KEY]
        val currentMetaHash = computeMetadataHash(mr)
        val metadataChanged = previousMetaHash == null || previousMetaHash != currentMetaHash

        val previousConfigHash = previousSnapshot[CONFIG_KEY]
        val configChanged = currentConfigHash != null &&
            (previousConfigHash == null || previousConfigHash != currentConfigHash)

        // Config change → full invalidation (rules/models may have changed)
        if (configChanged) {
            return DeltaResult(
                mr = mr,
                unchangedFiles = emptyList(),
                metadataChanged = true,
                configChanged = true,
            )
        }

        // File delta is INDEPENDENT of metadata - only compare file hashes
        val changed = mutableListOf<FileChange>()
        val unchanged = mutableListOf<String>()

        for (change in mr.changes) {
            val previousHash = previousSnapshot[change.path]
            val currentHash = change.diffContent?.let { sha256First64Bits(it) } ?: 0L

            if (previousHash == null || previousHash != currentHash) {
                changed.add(change)
            } else {
                unchanged.add(change.path)
            }
        }

        return DeltaResult(
            mr = mr.copy(changes = changed),
            unchangedFiles = unchanged,
            metadataChanged = metadataChanged,
            configChanged = false,
        )
    }

    private fun computeMetadataHash(mr: MergeRequest): Long {
        val combined = buildString {
            append(mr.title)
            append('\u0000')
            append(mr.description.orEmpty())
            append('\u0000')
            append(mr.commits.joinToString("\n") { it.message })
            append('\u0000')
            append(mr.labels.sorted().joinToString(","))
            append('\u0000')
            append(mr.draft.toString())
            append('\u0000')
            append(mr.state.name)
        }
        return sha256First64Bits(combined)
    }
}

/**
 * Result of a delta calculation between current MR and a previous snapshot.
 *
 * @property mr MR containing only changed/new files.
 * @property unchangedFiles Paths of files that were unchanged since the previous review.
 * @property metadataChanged True when MR-level metadata (title, description, commits, labels, draft, state)
 *   differs from the previous snapshot. MR-level findings must be re-evaluated when this is true.
 * @property configChanged True when the resolved config hash differs from the snapshot, indicating a full
 *   cache invalidation is required (rules or models may have changed).
 */
@Serializable
data class DeltaResult(
    val mr: MergeRequest,
    val unchangedFiles: List<String>,
    val metadataChanged: Boolean = false,
    val configChanged: Boolean = false,
) {
    /** Whether the delta contains any changes to review. */
    val hasChanges: Boolean get() = mr.changes.isNotEmpty()

    /**
     * Human-readable summary of what was filtered.
     *
     * @return A summary string, or null if no files were skipped.
     */
    fun summary(): String? =
        if (unchangedFiles.isEmpty()) {
            null
        } else {
            "(${unchangedFiles.size} unchanged files skipped: ${unchangedFiles.joinToString(", ")})"
        }
}
