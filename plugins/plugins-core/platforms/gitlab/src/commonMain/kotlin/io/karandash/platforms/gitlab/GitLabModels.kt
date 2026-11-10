package io.karandash.platforms.gitlab

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitLabDiffRefs(
    @SerialName("base_sha") val baseSha: String,
    @SerialName("head_sha") val headSha: String,
    @SerialName("start_sha") val startSha: String
)

@Serializable
data class GitLabMergeRequest(
    val id: Long,
    val iid: Int,
    val title: String,
    val description: String?,
    @SerialName("source_branch")
    val sourceBranch: String,
    @SerialName("target_branch")
    val targetBranch: String,
    val author: GitLabUser,
    val state: String,
    @SerialName("work_in_progress")
    val workInProgress: Boolean = false,
    val draft: Boolean = false,
    val labels: List<String> = emptyList(),
    @SerialName("approved_by")
    val approvedBy: List<GitLabApprover> = emptyList(),
    val reviewers: List<GitLabUser> = emptyList(),
    @SerialName("merge_status")
    val mergeStatus: String? = null,
    @SerialName("detailed_merge_status")
    val detailedMergeStatus: String? = null,
    @SerialName("has_conflicts")
    val hasConflicts: Boolean? = null,
    @SerialName("head_pipeline")
    val headPipeline: GitLabPipeline? = null,
    @SerialName("diff_refs")
    val diffRefs: GitLabDiffRefs? = null
)

@Serializable
data class GitLabDiscussion(
    val id: String
)

@Serializable
data class GitLabDiscussionDetail(
    val id: String,
    val notes: List<GitLabNote> = emptyList(),
    val resolved: Boolean = false,
)

@Serializable
data class GitLabUser(
    val id: Long,
    val username: String,
    val name: String,
    val email: String? = null
)

/** A project member from `GET /projects/:id/members/all`, with their role ([accessLevel], 30 = Developer). */
@Serializable
data class GitLabMember(
    val id: Long,
    val username: String,
    val name: String,
    @SerialName("access_level")
    val accessLevel: Int = 0,
)

@Serializable
data class GitLabApprover(
    val user: GitLabUser,
    @SerialName("approved_at")
    val approvedAt: String? = null
)

/** `GET /projects/:id/merge_requests/:iid/approvals` - the only response that lists `approved_by`. */
@Serializable
data class GitLabApprovals(
    @SerialName("approved_by")
    val approvedBy: List<GitLabApprover> = emptyList(),
)

@Serializable
data class GitLabCommit(
    val id: String,
    val short_id: String,
    val title: String,
    val message: String,
    @SerialName("author_name")
    val authorName: String,
    @SerialName("author_email")
    val authorEmail: String,
    @SerialName("created_at")
    val createdAt: String
)

@Serializable
data class GitLabDiff(
    @SerialName("old_path")
    val oldPath: String,
    @SerialName("new_path")
    val newPath: String,
    @SerialName("new_file")
    val newFile: Boolean,
    @SerialName("renamed_file")
    val renamedFile: Boolean,
    @SerialName("deleted_file")
    val deletedFile: Boolean,
    val diff: String
)

@Serializable
data class GitLabPipeline(
    val id: Long? = null,
    val status: String? = null
)

@Serializable
data class GitLabJob(
    val name: String,
    val status: String
)

/**
 * Request body for creating a diff discussion (inline comment) on a merge request.
 * Used by [GitLabDiscussionsClient.createDiffDiscussion] to avoid heterogeneous map serialization.
 */
@Serializable
data class GitLabCreateDiffDiscussionRequest(
    val body: String,
    val position: GitLabDiffPosition
)

/** Request body of `POST .../draft_notes`: a diff note held back until `bulk_publish`. */
@Serializable
data class GitLabCreateDraftNoteRequest(
    val note: String,
    val position: GitLabDiffPosition,
)

/**
 * Position payload for a diff discussion, specifying the exact location in the diff.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GitLabDiffPosition(
    @SerialName("base_sha") val baseSha: String,
    @SerialName("start_sha") val startSha: String,
    @SerialName("head_sha") val headSha: String,
    @SerialName("position_type") val positionType: String = "text",
    @SerialName("old_path") val oldPath: String,
    @SerialName("new_path") val newPath: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("new_line") val newLine: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("old_line") val oldLine: Int? = null,
)

@Serializable
data class GitLabNote(
    val id: Long,
    val body: String,
    @SerialName("author") val author: GitLabUser? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("system") val system: Boolean = false,
    @SerialName("noteable_id") val noteableId: Long? = null,
    @SerialName("noteable_type") val noteableType: String? = null,
    val resolvable: Boolean = false,
    val resolved: Boolean = false,
    @SerialName("resolved_by") val resolvedBy: GitLabUser? = null,
    val position: GitLabNotePosition? = null,
)

/**
 * Position metadata for a diff note, indicating which file and line the note targets.
 *
 * Only present on DiffNote-type notes (inline comments on merge request diffs).
 * Used for file-scoped resolution of inline comments in cache-aware comment management.
 */
@Serializable
data class GitLabNotePosition(
    @SerialName("new_path") val newPath: String? = null,
    @SerialName("old_path") val oldPath: String? = null,
    @SerialName("new_line") val newLine: Int? = null,
    @SerialName("old_line") val oldLine: Int? = null,
)

/** `GET .../notes/:note_id/award_emoji` item: a reaction on a note. */
@Serializable
data class GitLabAwardEmoji(
    val name: String,
    val user: GitLabUser? = null,
)

@Serializable
data class GitLabBlameEntry(
    val lines: List<String> = emptyList(),
    val commit: GitLabBlameCommit,
)

@Serializable
data class GitLabBlameCommit(
    val id: String,
    val message: String,
    @SerialName("author_name")
    val authorName: String,
    @SerialName("author_email")
    val authorEmail: String,
    @SerialName("authored_date")
    val authoredDate: String,
)
