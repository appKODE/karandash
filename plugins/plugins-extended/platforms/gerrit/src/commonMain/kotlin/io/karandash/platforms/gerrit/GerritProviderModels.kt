package io.karandash.platforms.gerrit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GerritChange(
    val id: String,
    @SerialName("_number")
    val number: Int,
    val subject: String,
    val project: String,
    val branch: String,
    val status: String,
    val owner: GerritAccount,
    val labels: Map<String, GerritLabelInfo> = emptyMap(),
    val reviewers: GerritReviewers = GerritReviewers(),
    @SerialName("work_in_progress")
    val workInProgress: Boolean = false,
    @SerialName("current_revision")
    val currentRevision: String? = null,
    val revisions: Map<String, GerritRevision> = emptyMap(),
    @SerialName("hashtags")
    val hashtags: List<String> = emptyList(),
    val mergeable: Boolean? = null,
)

@Serializable
data class GerritAccount(
    @SerialName("_account_id")
    val accountId: Long,
    val name: String? = null,
    val email: String? = null,
    val username: String? = null
)

@Serializable
data class GerritLabelInfo(
    val approved: GerritAccount? = null,
    val rejected: GerritAccount? = null,
    val all: List<GerritApprovalInfo> = emptyList()
)

@Serializable
data class GerritApprovalInfo(
    @SerialName("_account_id")
    val accountId: Long,
    val name: String? = null,
    val email: String? = null,
    val username: String? = null,
    val value: Int? = null,
    val date: String? = null
)

@Serializable
data class GerritReviewers(
    @SerialName("REVIEWER")
    val reviewers: List<GerritAccount> = emptyList(),
    @SerialName("CC")
    val cc: List<GerritAccount> = emptyList()
)

@Serializable
data class GerritRevision(
    val kind: String,
    @SerialName("_number")
    val number: Int,
    val created: String,
    val uploader: GerritAccount,
    val ref: String,
    val commit: GerritCommitInfo? = null,
    val files: Map<String, GerritFileInfo> = emptyMap()
)

@Serializable
data class GerritCommitInfo(
    val commit: String,
    val parents: List<GerritParent> = emptyList(),
    val author: GerritGitPerson,
    val committer: GerritGitPerson,
    val subject: String,
    val message: String
)

@Serializable
data class GerritParent(
    val commit: String,
    val subject: String? = null
)

@Serializable
data class GerritGitPerson(
    val name: String,
    val email: String,
    val date: String
)

@Serializable
data class GerritFileInfo(
    val status: String? = null,
    @SerialName("lines_inserted")
    val linesInserted: Int = 0,
    @SerialName("lines_deleted")
    val linesDeleted: Int = 0,
    @SerialName("size_delta")
    val sizeDelta: Long = 0,
    val size: Long = 0
)
