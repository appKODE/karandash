package io.karandash.enrichment.gitlabissue

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitLabIssueDetails(
    val iid: Int,
    val title: String,
    val description: String?,
    val state: String,
    val labels: List<String> = emptyList(),
    val milestone: String? = null,
    val assignees: List<String> = emptyList(),
)

@Serializable
internal data class GitLabIssueResponse(
    val iid: Int,
    val title: String,
    val description: String? = null,
    val state: String,
    val labels: List<String> = emptyList(),
    val milestone: GitLabMilestoneResponse? = null,
    val assignees: List<GitLabUserResponse> = emptyList(),
    @SerialName("web_url")
    val webUrl: String = "",
)

@Serializable
internal data class GitLabMilestoneResponse(
    val title: String,
)

@Serializable
internal data class GitLabUserResponse(
    val username: String,
)
