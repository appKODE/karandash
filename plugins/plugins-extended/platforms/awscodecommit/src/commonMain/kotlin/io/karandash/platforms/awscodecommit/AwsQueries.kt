@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.awscodecommit

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal

/** Pull requests read at most per search; each one costs a `GetPullRequest` call. */
private const val SEARCH_SCAN_CAP = 100

/**
 * Pull requests of [repositoryName] in every status, newest first, with [text] in the title or description and
 * [author] as the user part of the author ARN, at most [limit]. `ListPullRequests` filters only by a full author ARN
 * and has no text filter, so both are matched on each pull request read.
 */
internal suspend fun searchPullRequests(
    client: AwsCodeSuiteClient,
    repositoryName: String,
    text: String?,
    author: String?,
    limit: Int,
): List<MergeRequestSearchHit> {
    // limit: reads up to SEARCH_SCAN_CAP newest pull requests one by one; older matches are missed.
    val ids = client.listPullRequestIds(repositoryName, status = null).sortedByDescending { it.toLongOrNull() ?: 0L }
    val hits = mutableListOf<MergeRequestSearchHit>()
    for (id in ids.take(SEARCH_SCAN_CAP)) {
        if (hits.size >= limit) break
        val pr = client.fetchPullRequest(id)
        val username = AwsCodeSuiteAdapter.extractUser(pr.authorArn).username
        val textMatches = text.isNullOrBlank() || pr.title.contains(text, true) || pr.description.orEmpty().contains(text, true)
        if (textMatches && (author.isNullOrBlank() || username == author)) hits += pr.toSearchHit(username)
    }
    return hits
}

/** `OPEN` is open; a `CLOSED` pull request is merged when a target says so, else closed. */
private fun AwsPullRequest.toSearchHit(username: String): MergeRequestSearchHit =
    MergeRequestSearchHit(
        id = pullRequestId,
        title = title,
        state = when {
            pullRequestStatus == "OPEN" -> MergeRequestSearchHit.OPENED
            pullRequestTargets.any { it.mergeMetadata?.isMerged == true } -> MergeRequestSearchHit.MERGED
            else -> MergeRequestSearchHit.CLOSED
        },
        author = username,
    )

/** Pull request comments as approval signals of their authors; CodeCommit reactions are not read. */
internal fun approvalSignals(comments: List<AwsComment>): List<ApprovalSignal> =
    comments.mapNotNull { comment ->
        val arn = comment.authorArn ?: return@mapNotNull null
        val text = comment.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ApprovalSignal(AwsCodeSuiteAdapter.extractUser(arn), ApprovalMethod.COMMENT_KEYWORD, text, comment.creationDate)
    }
