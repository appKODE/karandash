package io.karandash.enrichment.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubIssueDetails(
    val number: Int,
    val title: String,
    val body: String?,
    val state: String,
    val labels: List<String> = emptyList(),
    val milestone: String? = null,
    val assignees: List<String> = emptyList(),
    @SerialName("linkedPrNumbers")
    val linkedPrNumbers: List<Int> = emptyList(),
)

@Serializable
internal data class GitHubIssueResponse(
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String,
    val labels: List<GitHubLabelResponse> = emptyList(),
    val milestone: GitHubMilestoneResponse? = null,
    val assignees: List<GitHubUserResponse> = emptyList(),
)

@Serializable
internal data class GitHubLabelResponse(
    val name: String,
)

@Serializable
internal data class GitHubMilestoneResponse(
    val title: String,
)

@Serializable
internal data class GitHubUserResponse(
    val login: String,
)
