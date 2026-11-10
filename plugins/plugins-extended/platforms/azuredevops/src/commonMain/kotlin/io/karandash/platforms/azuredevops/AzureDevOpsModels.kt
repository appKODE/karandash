package io.karandash.platforms.azuredevops

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AzureDevOpsPullRequest(
    val pullRequestId: Int,
    val title: String,
    val description: String? = null,
    val status: String,
    val createdBy: AzureDevOpsUser,
    val creationDate: String,
    val sourceRefName: String,
    val targetRefName: String,
    val isDraft: Boolean = false,
    val labels: List<AzureDevOpsLabel> = emptyList(),
    val reviewers: List<AzureDevOpsReviewer> = emptyList(),
    @SerialName("lastMergeSourceCommit")
    val lastMergeSourceCommit: AzureDevOpsCommitRef? = null,
    @SerialName("lastMergeTargetCommit")
    val lastMergeTargetCommit: AzureDevOpsCommitRef? = null,
    val mergeStatus: String? = null,
)

@Serializable
data class AzureDevOpsCommitRef(
    val commitId: String
)

@Serializable
data class AzureDevOpsUser(
    val id: String,
    val displayName: String,
    val uniqueName: String? = null,
    val imageUrl: String? = null
)

@Serializable
data class AzureDevOpsLabel(
    val id: String,
    val name: String,
    val active: Boolean = true
)

@Serializable
data class AzureDevOpsReviewer(
    val reviewerUrl: String? = null,
    val vote: Int = 0,
    val id: String,
    val displayName: String,
    val uniqueName: String? = null,
    val isRequired: Boolean = false
)

@Serializable
data class AzureDevOpsCommit(
    val commitId: String,
    val comment: String,
    val author: AzureDevOpsCommitAuthor,
    val committer: AzureDevOpsCommitAuthor,
    @SerialName("changeCounts")
    val changeCounts: AzureDevOpsChangeCounts? = null,
    val url: String? = null
)

@Serializable
data class AzureDevOpsCommitAuthor(
    val name: String,
    val email: String,
    val date: String
)

@Serializable
data class AzureDevOpsChangeCounts(
    @SerialName("Add")
    val add: Int = 0,
    @SerialName("Edit")
    val edit: Int = 0,
    @SerialName("Delete")
    val delete: Int = 0
)

@Serializable
data class AzureDevOpsCommitsResponse(
    val value: List<AzureDevOpsCommit>,
    val count: Int
)

@Serializable
data class AzureDevOpsItem(
    val path: String,
    val url: String? = null,
    /** Blob id of the file after the change (absent for deletions). */
    val objectId: String? = null,
    /** Blob id of the file before the change (absent for additions). */
    val originalObjectId: String? = null
)

@Serializable
data class AzureDevOpsIteration(
    val id: Int
)

@Serializable
data class AzureDevOpsIterationsResponse(
    val value: List<AzureDevOpsIteration> = emptyList()
)

@Serializable
data class AzureDevOpsChangesResponse(
    val changeEntries: List<AzureDevOpsChangeEntry> = emptyList(),
    /** Offset of the next page, or 0 when this is the last one. */
    val nextSkip: Int = 0,
)

@Serializable
data class AzureDevOpsChangeEntry(
    val item: AzureDevOpsItem? = null,
    val changeType: String
)

/** `GET .../pullRequests/{id}/statuses` (`state`: succeeded, failed, pending, error, notApplicable, notSet). */
@Serializable
data class AzureDevOpsPullRequestStatusesResponse(
    val value: List<AzureDevOpsPullRequestStatus> = emptyList(),
)

@Serializable
data class AzureDevOpsPullRequestStatus(
    val state: String = "notSet",
    val context: AzureDevOpsStatusContext,
)

@Serializable
data class AzureDevOpsStatusContext(
    val name: String,
    val genre: String? = null,
)
