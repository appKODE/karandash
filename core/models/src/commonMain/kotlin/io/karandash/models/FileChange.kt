package io.karandash.models

import kotlinx.serialization.Serializable

/**
 * One changed file of a merge request.
 *
 * @property oldPath The path before a rename; null when the file was not renamed or the platform does not say.
 */
@Serializable
data class FileChange(
    val path: String,
    val changeType: ChangeType,
    val additions: Int = 0,
    val deletions: Int = 0,
    val diffContent: String? = null,
    val newContent: String? = null,
    val blameData: List<BlameEntry> = emptyList(),
    val oldPath: String? = null,
)

@Serializable
enum class ChangeType {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
}
