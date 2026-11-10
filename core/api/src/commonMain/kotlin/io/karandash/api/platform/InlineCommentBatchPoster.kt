package io.karandash.api.platform

import io.karandash.api.finding.InlineCommentData
import io.karandash.models.DiffRefs

/**
 * Implemented by a platform service that posts all of a run's inline comments as one review (for example Gitea,
 * where every review notifies the author), instead of one review per comment.
 *
 * The host prefers it over [io.karandash.api.module.PlatformServiceModule.postInlineComment] when a run has
 * several comments, and falls back to posting them one by one when the batch call fails.
 */
public fun interface InlineCommentBatchPoster {
    /** Posts [comments] (bodies already rendered for the platform) as one review and returns its id. */
    public suspend fun postInlineComments(
        projectId: String,
        mrId: String,
        comments: List<InlineCommentData>,
        diffRefs: DiffRefs,
    ): String

    /**
     * Posts [comments] and the run's [summary] as one review, where the platform keeps the summary in the review itself
     * (Gerrit: one ReviewInput, one notification instead of two).
     *
     * @return `false` (the default, nothing posted) when the platform does not: the caller posts the comments and
     *   the summary on their own.
     */
    public suspend fun postReviewWithSummary(
        projectId: String,
        mrId: String,
        comments: List<InlineCommentData>,
        diffRefs: DiffRefs,
        summary: String,
    ): Boolean = false
}
