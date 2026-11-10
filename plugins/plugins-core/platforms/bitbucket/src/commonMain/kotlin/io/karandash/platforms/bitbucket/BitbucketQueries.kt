@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.bitbucket

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal
import io.karandash.models.User
import kotlinx.serialization.Serializable

/** One page of the pull request list, as far as search reads it. */
@Serializable
internal data class BitbucketPullRequestHits(
    val values: List<BitbucketPullRequestHit> = emptyList(),
)

@Serializable
internal data class BitbucketPullRequestHit(
    val id: Int,
    val title: String = "",
    val state: String = "",
    val author: BitbucketUser? = null,
    val links: BitbucketPullRequestLinks? = null,
)

@Serializable
internal data class BitbucketPullRequestLinks(
    val html: BitbucketHref? = null,
)

@Serializable
internal data class BitbucketHref(
    val href: String? = null,
)

/** The name a Bitbucket account goes by in findings; the account `username` is gone from most payloads since 2019. */
internal fun BitbucketUser.handle(): String = username ?: nickname ?: displayName

/**
 * BBQL for a pull request search: [text] in the title or description and/or [author] by nickname; blank when
 * neither is given. Double quotes and backslashes are dropped, BBQL strings cannot escape them reliably.
 */
internal fun searchQuery(
    text: String?,
    author: String?,
): String {
    fun clean(value: String) = value.replace("\"", "").replace("\\", "")
    return listOfNotNull(
        text?.takeIf { it.isNotBlank() }?.let { "(title ~ \"${clean(it)}\" OR description ~ \"${clean(it)}\")" },
        author?.takeIf { it.isNotBlank() }?.let { "author.nickname = \"${clean(it)}\"" },
    ).joinToString(" AND ")
}

/** `OPEN` is open, `MERGED` merged; `DECLINED` and `SUPERSEDED` are closed. */
internal fun BitbucketPullRequestHit.toSearchHit(): MergeRequestSearchHit =
    MergeRequestSearchHit(
        id = id.toString(),
        title = title,
        state = when (state) {
            "OPEN" -> MergeRequestSearchHit.OPENED
            "MERGED" -> MergeRequestSearchHit.MERGED
            else -> MergeRequestSearchHit.CLOSED
        },
        author = author?.handle(),
        webUrl = links?.html?.href,
    )

/** Pull request comments as approval signals of their authors; Bitbucket Cloud has no reactions API. */
internal fun approvalSignals(comments: List<BitbucketComment>): List<ApprovalSignal> =
    comments.mapNotNull { comment ->
        val user = comment.user ?: return@mapNotNull null
        val text = comment.content?.raw?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ApprovalSignal(User(user.handle(), user.displayName, null), ApprovalMethod.COMMENT_KEYWORD, text, comment.createdOn)
    }
