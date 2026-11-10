@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.azuredevops

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal
import io.karandash.models.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One page of `GET .../pullrequests`, as far as search reads it. */
@Serializable
internal data class AzurePullRequestHits(
    val value: List<AzurePullRequestHit> = emptyList(),
)

@Serializable
internal data class AzurePullRequestHit(
    val pullRequestId: Int,
    val title: String = "",
    val description: String? = null,
    val status: String = "",
    val createdBy: AzureUser? = null,
    val repository: AzureRepositoryRef? = null,
)

@Serializable
internal data class AzureRepositoryRef(
    val name: String? = null,
)

/** `GET {org}/_apis/connectionData`: the authenticated identity and its sign-in account. */
@Serializable
internal data class AzureConnectionData(
    val authenticatedUser: AzureAuthenticatedUser? = null,
)

@Serializable
internal data class AzureAuthenticatedUser(
    val id: String? = null,
    val providerDisplayName: String? = null,
    val properties: AzureIdentityProperties? = null,
)

@Serializable
internal data class AzureIdentityProperties(
    @SerialName("Account")
    val account: AzurePropertyValue? = null,
)

@Serializable
internal data class AzurePropertyValue(
    @SerialName("\$value")
    val value: String? = null,
)

/** The account name (`uniqueName` in pull request payloads), else the identity id. */
internal fun AzureConnectionData.username(): String? = authenticatedUser?.let { it.properties?.account?.value ?: it.id }

/** Whether [text] is in the title or description; the pull request list API has no text filter. */
internal fun AzurePullRequestHit.matches(text: String?): Boolean =
    text.isNullOrBlank() || title.contains(text, ignoreCase = true) || description.orEmpty().contains(text, ignoreCase = true)

/** `active` is open, `completed` merged; `abandoned` is closed. */
internal fun AzurePullRequestHit.toSearchHit(webBase: String): MergeRequestSearchHit =
    MergeRequestSearchHit(
        id = pullRequestId.toString(),
        title = title,
        state = when (status) {
            "active" -> MergeRequestSearchHit.OPENED
            "completed" -> MergeRequestSearchHit.MERGED
            else -> MergeRequestSearchHit.CLOSED
        },
        author = createdBy?.let { it.uniqueName ?: it.id },
        webUrl = repository?.name?.let { "$webBase/_git/$it/pullrequest/$pullRequestId" },
    )

/** Text comments in the pull request's threads as approval signals of their authors; system and code-change comments are skipped. */
internal fun approvalSignals(threads: List<AzureThread>): List<ApprovalSignal> =
    threads.flatMap { it.comments }.mapNotNull { comment ->
        val author = comment.author ?: return@mapNotNull null
        val text = comment.content?.takeIf { it.isNotBlank() && (comment.commentType ?: "text") == "text" } ?: return@mapNotNull null
        val username = author.uniqueName ?: author.id ?: return@mapNotNull null
        ApprovalSignal(User(username, author.displayName ?: username), ApprovalMethod.COMMENT_KEYWORD, text, comment.publishedDate)
    }
