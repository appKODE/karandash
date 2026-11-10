package io.karandash.platforms.gitea

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GiteaPullRequest(
    val id: Long,
    val number: Int,
    val title: String,
    val body: String?,
    @SerialName("html_url")
    val htmlUrl: String,
    val state: String,
    val user: GiteaUser,
    @SerialName("head")
    val head: GiteaBranch,
    @SerialName("base")
    val base: GiteaBranch,
    val labels: List<GiteaLabel> = emptyList(),
    val draft: Boolean = false,
    val mergeable: Boolean? = null,
    val merged: Boolean = false,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("merged_at")
    val mergedAt: String? = null,
    @SerialName("requested_reviewers")
    val requestedReviewers: List<GiteaUser> = emptyList()
)

@Serializable
data class GiteaUser(
    val id: Long,
    val login: String,
    @SerialName("full_name")
    val fullName: String? = null,
    val email: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null
)

@Serializable
data class GiteaBranch(
    val ref: String,
    val sha: String,
    val repo: GiteaRepository? = null
)

@Serializable
data class GiteaRepository(
    val id: Long,
    val name: String,
    @SerialName("full_name")
    val fullName: String,
    val owner: GiteaUser,
    @SerialName("html_url")
    val htmlUrl: String
)

@Serializable
data class GiteaLabel(
    val id: Long,
    val name: String,
    val color: String? = null,
    val description: String? = null
)

@Serializable
data class GiteaCommit(
    val sha: String,
    val commit: GiteaCommitInfo,
    @SerialName("html_url")
    val htmlUrl: String? = null
)

@Serializable
data class GiteaCommitInfo(
    val message: String,
    val author: GiteaCommitAuthor,
    val committer: GiteaCommitAuthor
)

@Serializable
data class GiteaCommitAuthor(
    val name: String,
    val email: String,
    val date: String
)

@Serializable
data class GiteaReview(
    val id: Long,
    val user: GiteaUser,
    val state: String,
    @SerialName("submitted_at")
    val submittedAt: String? = null,
    val dismissed: Boolean = false,
    val stale: Boolean = false
)

@Serializable
data class GiteaFile(
    val filename: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    @SerialName("previous_filename")
    val previousFilename: String? = null
)

/** `GET /repos/{owner}/{repo}/commits/{ref}/status`. */
@Serializable
data class GiteaCombinedStatus(
    val state: String? = null,
    val statuses: List<GiteaCommitStatus> = emptyList(),
)

@Serializable
data class GiteaCommitStatus(
    val context: String,
    val status: String,
)

/** `GET /repos/{owner}/{repo}/collaborators/{username}/permission`: the user's legacy base role. */
@Serializable
internal data class RepoPermission(
    val permission: String,
)

/** The [RepoPermission.permission] values that may push. */
internal val WRITE_PERMISSIONS = setOf("write", "admin", "owner")

/** `GET /repos/{owner}/{repo}/issues/{index}/reactions` item; [content] is `+1`, `-1`, `heart`, ... */
@Serializable
internal data class GiteaReaction(
    val content: String,
    val user: GiteaUser? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** `GET /repos/{owner}/{repo}/issues?type=pulls` item: a pull request listed as an issue. */
@Serializable
internal data class GiteaIssue(
    val number: Int,
    val title: String = "",
    val state: String = "",
    val user: GiteaUser? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("pull_request") val pullRequest: GiteaPullRequestMeta? = null,
)

/** The `pull_request` block of [GiteaIssue]. */
@Serializable
internal data class GiteaPullRequestMeta(
    val merged: Boolean = false,
)
