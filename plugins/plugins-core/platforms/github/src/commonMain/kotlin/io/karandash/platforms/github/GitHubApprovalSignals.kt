package io.karandash.platforms.github

import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal
import io.karandash.models.User

/** Comments and reactions on the pull request's conversation, each as an approval signal of its author. */
internal suspend fun GitHubClient.approvalSignals(
    owner: String,
    repo: String,
    prNumber: Int,
): List<ApprovalSignal> {
    val comments = listIssueComments(owner, repo, prNumber).mapNotNull { comment ->
        comment.user?.let { ApprovalSignal(it.toUser(), ApprovalMethod.COMMENT_KEYWORD, comment.body, comment.createdAt) }
    }
    val reactions = listIssueReactions(owner, repo, prNumber).mapNotNull { reaction ->
        reaction.user?.let { ApprovalSignal(it.toUser(), ApprovalMethod.EMOJI_REACTION, reaction.content, reaction.createdAt) }
    }
    return comments + reactions
}

private fun GitHubUser.toUser() = User(username = login, name = name ?: login, email = email)
