package io.karandash.platforms.gitea

import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition

/** First Gitea release with `POST pulls/comments/{id}/resolve` (go-gitea/gitea#36441). */
private val RESOLVE_SINCE = listOf(1, 26)

/**
 * Whether a server reporting [version] can resolve review conversations. Forgejo reports `<forgejo>+gitea-<x.y.z>`,
 * so the Gitea part after `+gitea-` is compared when present; an unknown version cannot.
 */
internal fun canResolveConversations(version: String?): Boolean {
    val gitea = version?.substringAfter("+gitea-")?.substringBefore('+') ?: return false
    val parts = gitea.split('.').take(2).map { it.takeWhile(Char::isDigit).toIntOrNull() ?: return false }
    return parts.size == 2 && (parts[0] > RESOLVE_SINCE[0] || (parts[0] == RESOLVE_SINCE[0] && parts[1] >= RESOLVE_SINCE[1]))
}

/**
 * Gitea groups review comments into a conversation by file and line, and the API carries no reply link, so a thread
 * is every comment on the same path and line, oldest first; its id is the first comment's id.
 */
internal fun giteaReviewThreads(comments: List<GiteaPullReviewComment>): List<ReviewThread> =
    comments
        .sortedBy { it.id }
        .groupBy { Triple(it.path, it.position, it.originalPosition) }
        .values
        .map { notes ->
            val root = notes.first()
            ReviewThread(
                id = root.id.toString(),
                notes = notes.map { it.toReviewNote() },
                resolved = notes.any { it.resolver != null },
                position = root.path?.let { path ->
                    ReviewThreadPosition(
                        newPath = path,
                        oldPath = path,
                        newLine = root.position.takeIf { it > 0 },
                        oldLine = root.originalPosition.takeIf { it > 0 },
                    )
                },
            )
        }

/** Posts [body] into conversation [threadId]: a new review commenting on the root's file, line and side. */
internal suspend fun GiteaClient.replyToConversation(
    owner: String,
    repo: String,
    prId: Int,
    threadId: String,
    body: String,
) {
    val root = listPullReviewComments(owner, repo, prId).firstOrNull { it.id.toString() == threadId }
        ?: error("Gitea review comment $threadId not found on pull request $prId")
    createPullReview(owner, repo, prId, checkNotNull(root.commitId) { "no commit on $threadId" }, listOf(giteaReply(root, body)))
}

/** A reply joins [root]'s conversation by commenting on the same file, line and side in a new review. */
private fun giteaReply(
    root: GiteaPullReviewComment,
    body: String,
): GiteaReviewCommentRequest =
    GiteaReviewCommentRequest(
        path = checkNotNull(root.path) { "Gitea review comment ${root.id} has no file" },
        newPosition = root.position,
        body = body,
        oldPosition = root.originalPosition,
    )

private fun GiteaPullReviewComment.toReviewNote(): ReviewNote =
    ReviewNote(
        id = id.toString(),
        body = body,
        author = user?.login,
        createdAt = createdAt,
        resolved = resolver != null,
        resolvedBy = resolver?.login,
    )
