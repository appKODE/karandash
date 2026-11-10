package io.karandash.platforms.azuredevops

import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition

/** Thread statuses Azure DevOps counts as settled (`fixed`, `wontFix`, `closed`, `byDesign`). */
private val RESOLVED_STATUSES = setOf("fixed", "wontFix", "closed", "byDesign")

/** This thread as an SDK [ReviewThread]; a thread without an id (never returned by the API) is dropped. */
internal fun AzureThread.toReviewThread(): ReviewThread? {
    val threadId = id ?: return null
    val resolved = status in RESOLVED_STATUSES
    return ReviewThread(
        id = threadId.toString(),
        resolved = resolved,
        position = threadContext?.let {
            val path = it.filePath.removePrefix("/")
            ReviewThreadPosition(newPath = path, oldPath = path, newLine = it.rightFileEnd?.line ?: it.rightFileStart?.line)
        },
        notes = comments.map {
            ReviewNote(
                id = it.id?.toString().orEmpty(),
                body = it.content.orEmpty(),
                author = it.author?.uniqueName ?: it.author?.displayName,
                createdAt = it.publishedDate,
                system = it.commentType == "system",
                resolvable = true,
                resolved = resolved,
            )
        },
    )
}

/** The numeric thread id of [threadId] (or of a `thread:comment` id). */
internal fun azureThreadId(threadId: String): Int =
    requireNotNull(threadId.substringBefore(':').toIntOrNull()) { "Azure DevOps thread id must be a number: $threadId" }
