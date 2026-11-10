package io.karandash.platforms.awscodecommit

import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition

/**
 * CodeCommit has no thread object: a comment without `inReplyTo` opens a thread and every reply chains to it.
 * The thread id is the opening comment's id, which [AwsCodeSuiteClient.postCommentReply] answers. CodeCommit
 * cannot resolve comments, so no thread is ever resolved.
 */
internal fun awsReviewThreads(comments: List<AwsComment>): List<ReviewThread> {
    val byId = comments.associateBy { it.commentId }
    return comments
        .groupBy { comment ->
            // take() bounds the walk, so a reply cycle cannot loop forever
            generateSequence(comment) { it.inReplyTo?.let(byId::get) }.take(comments.size).last().commentId
        }.map { (rootId, notes) ->
            val root = byId.getValue(rootId)
            ReviewThread(
                id = rootId,
                notes = notes.map { it.toReviewNote() },
                position = root.filePath?.let { path ->
                    val line = root.filePosition?.toInt()
                    val before = root.relativeFileVersion == "BEFORE"
                    ReviewThreadPosition(
                        newPath = path,
                        oldPath = path,
                        newLine = line.takeUnless { before },
                        oldLine = line.takeIf { before },
                    )
                },
            )
        }
}

private fun AwsComment.toReviewNote(): ReviewNote =
    ReviewNote(
        id = commentId,
        body = content.orEmpty(),
        author = authorArn?.substringAfterLast('/'),
        createdAt = creationDate,
    )
