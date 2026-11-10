package io.karandash.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MergeRequest(
    val id: String,
    val iid: Int,
    val title: String,
    val description: String?,
    @SerialName("source_branch")
    val sourceBranch: String,
    @SerialName("target_branch")
    val targetBranch: String,
    val author: User,
    /**
     * The MR/PR state.
     * Adapters normalize platform-specific state strings to this enum.
     */
    val state: MergeRequestState,
    val draft: Boolean,
    val labels: List<String>,
    val commits: List<Commit>,
    val changes: List<FileChange>,
    val approvals: List<Approval> = emptyList(),
    val reviewers: List<User> = emptyList(),
    @SerialName("merge_status")
    val mergeStatus: String? = null,
    @SerialName("has_conflicts")
    val hasConflicts: Boolean? = null,
    @SerialName("ci_status")
    val ciStatus: String? = null,
    @SerialName("ci_jobs")
    val ciJobs: List<CiJobStatus> = emptyList(),
    @SerialName("diff_refs")
    val diffRefs: DiffRefs? = null,
    /** True when a page cap (`http.pagination.maxPages`) stopped the commit fetch: [commits] is only the first part. */
    @SerialName("commits_truncated")
    val commitsTruncated: Boolean = false,
    /**
     * The platform's own count of the MR's commits, when it reports one (GitHub). It can exceed [commits]: GitHub lists
     * at most 250 commits of a pull request whatever the page cap.
     */
    @SerialName("commits_total")
    val commitsTotal: Int? = null,
)

@Serializable
data class Approval(
    val user: User,
    @SerialName("approved_at")
    val approvedAt: String? = null,
    val state: ReviewState = ReviewState.APPROVED,
    val method: ApprovalMethod = ApprovalMethod.CODE_REVIEW,
)

@Serializable
data class CiJobStatus(
    val name: String,
    val status: String,
)
