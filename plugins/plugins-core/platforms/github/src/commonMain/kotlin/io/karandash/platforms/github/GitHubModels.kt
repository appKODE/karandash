package io.karandash.platforms.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubPullRequest(
    val id: Long,
    val number: Int,
    val title: String,
    val body: String?,
    @SerialName("html_url")
    val htmlUrl: String,
    val state: String,
    val draft: Boolean,
    val user: GitHubUser,
    val head: GitHubRef,
    val base: GitHubRef,
    val labels: List<GitHubLabel> = emptyList(),
    @SerialName("requested_reviewers")
    val requestedReviewers: List<GitHubUser> = emptyList(),
    val mergeable: Boolean? = null,
    @SerialName("mergeable_state")
    val mergeableState: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    /** GraphQL global id; the draft mutations address the PR by it. */
    @SerialName("node_id")
    val nodeId: String? = null,
    /** Commit count; the commits endpoint lists at most 250 of them. */
    val commits: Int? = null,
)

@Serializable
data class GitHubUser(
    val login: String,
    val id: Long,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    val name: String? = null,
    val email: String? = null
)

@Serializable
data class GitHubRef(
    val ref: String,
    val sha: String,
    val label: String
)

@Serializable
data class GitHubLabel(
    val name: String,
    val color: String? = null
)

@Serializable
data class GitHubCommit(
    val sha: String,
    val commit: GitHubCommitDetail
)

/** `GET /repos/{owner}/{repo}/compare/{base}...{head}`, only the commits. */
@Serializable
data class GitHubCompare(
    val commits: List<GitHubCommit> = emptyList(),
)

@Serializable
data class GitHubCommitDetail(
    val message: String,
    val author: GitHubCommitAuthor
)

@Serializable
data class GitHubCommitAuthor(
    val name: String,
    val email: String,
    val date: String
)

@Serializable
data class GitHubReview(
    val id: Long,
    val user: GitHubUser,
    val state: String,
    @SerialName("submitted_at")
    val submittedAt: String? = null
)

@Serializable
data class GitHubFile(
    val filename: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String? = null
)

@Serializable
data class GitHubComment(
    val id: Long,
    val body: String,
    @SerialName("user") val user: GitHubUser? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("issue_url") val issueUrl: String? = null
)

@Serializable
data class GitHubPullRequestReviewComment(
    val id: Long,
    val body: String = "",
    val path: String? = null,
    val reactions: GitHubReactions? = null,
)

/** `GET /issues/:n/reactions` item; [content] is `+1`, `-1`, `heart`, ... */
@Serializable
data class GitHubReaction(
    val content: String,
    val user: GitHubUser? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Reaction rollup GitHub includes in comment responses. */
@Serializable
data class GitHubReactions(
    @SerialName("-1") val thumbsDown: Int = 0,
)

/** `GET /repos/{owner}/{repo}/commits/{ref}/status` (legacy commit statuses). */
@Serializable
data class GitHubCombinedStatus(
    val state: String,
    val statuses: List<GitHubStatus> = emptyList(),
)

@Serializable
data class GitHubStatus(
    val context: String,
    val state: String,
)

/** `GET /repos/{owner}/{repo}/commits/{ref}/check-runs` (GitHub Actions and other check apps). */
@Serializable
data class GitHubCheckRuns(
    @SerialName("check_runs")
    val checkRuns: List<GitHubCheckRun> = emptyList(),
)

@Serializable
data class GitHubCheckRun(
    val name: String,
    val status: String,
    val conclusion: String? = null,
)

/** `GET /rate_limit` response; only the core REST bucket is read. */
@Serializable
data class GitHubRateLimitResponse(
    val rate: GitHubRate,
)

@Serializable
data class GitHubRate(
    val limit: Int,
    val remaining: Int,
    val reset: Long,
)

/** `GET /repos/{owner}/{repo}/collaborators/{username}/permission`: the user's legacy base role. */
@Serializable
internal data class RepoPermission(
    val permission: String,
)

/** The [RepoPermission.permission] values that may push. */
internal val WRITE_PERMISSIONS = setOf("write", "admin", "owner")
