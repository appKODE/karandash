@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gitea

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal

/** Comments and reactions on the pull request's conversation, each as an approval signal of its author. */
internal fun approvalSignals(
    comments: List<GiteaComment>,
    reactions: List<GiteaReaction>,
): List<ApprovalSignal> {
    val fromComments = comments.mapNotNull { comment ->
        comment.user?.let { ApprovalSignal(GiteaAdapter.toUser(it), ApprovalMethod.COMMENT_KEYWORD, comment.body, comment.createdAt) }
    }
    val fromReactions = reactions.mapNotNull { reaction ->
        reaction.user?.let { ApprovalSignal(GiteaAdapter.toUser(it), ApprovalMethod.EMOJI_REACTION, reaction.content, reaction.createdAt) }
    }
    return fromComments + fromReactions
}

/** [GiteaIssue] as a search hit; a closed pull request is merged when its `pull_request.merged` is set. */
internal fun GiteaIssue.toSearchHit(): MergeRequestSearchHit =
    MergeRequestSearchHit(
        id = number.toString(),
        title = title,
        state = when {
            state == "open" -> MergeRequestSearchHit.OPENED
            pullRequest?.merged == true -> MergeRequestSearchHit.MERGED
            else -> MergeRequestSearchHit.CLOSED
        },
        author = user?.login,
        webUrl = htmlUrl,
    )
