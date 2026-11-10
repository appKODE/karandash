package io.karandash.api.platform

import io.karandash.logger.Logger

/**
 * Optional platform operations a caller can ask about before calling them, via
 * [PlatformServiceModule.supports][io.karandash.api.module.PlatformServiceModule.supports].
 *
 * A platform declares each one it implements. An operation it does not declare fails with
 * [UnsupportedPlatformOperationException]: thrown, or as `Result.failure` for `Result`-returning methods. The query
 * methods ([FILE_CONTENT], [SEARCH], [LIST_OPEN], [APPROVAL_SIGNALS], [DISMISSED_INLINE], `listReviewThreads`) throw
 * it too; their `null` stays a legal "cannot tell" answer, and [nullIfUnsupported] folds the two together.
 */
public enum class PlatformOperation {
    /** `mergeMr`, `closeMr`. */
    MERGE,

    /** `addReviewers`, `removeReviewers`. */
    MANAGE_REVIEWERS,

    /** `postInlineComment`. */
    POST_INLINE_COMMENTS,

    /** `resolveThread`, and `resolveMarkedInlineComments` resolving the thread (not only superseding the comment). */
    RESOLVE_DISCUSSIONS,

    /** `addLabels`, `removeLabels`. */
    LABELS,

    /** `setDraft`. */
    DRAFT,

    /** `approve`, `unapprove`. */
    APPROVE,

    /** `postComment`, `updateComment`, `findExistingComment`. */
    COMMENTS,

    /** `reopenMr`. */
    REOPEN,

    /** `fetchFileContent`. */
    FILE_CONTENT,

    /** `listReviewThreads`, `replyToThread`, `updateNote`. */
    REVIEW_THREADS,

    /** `searchMergeRequests`. */
    SEARCH,

    /** `listOpenMergeRequestIds`. */
    LIST_OPEN,

    /** `fetchApprovalSignals`. */
    APPROVAL_SIGNALS,

    /** `listDismissedInlineComments`. */
    DISMISSED_INLINE,

    /** `postInlineComment` honours `startLine` (a multi-line range); without it the comment lands on `line` only. */
    INLINE_RANGES,

    /** `renderSuggestionBlock` emits the platform's native "apply suggestion" syntax, not the markdown diff fallback. */
    SUGGESTIONS,

    /** The service is an [InlineCommentBatchPoster]: all inline comments of a run are posted as one review. */
    BATCH_INLINE,

    /** The service is a [ResultStatusPublisher]: the verdict is shown on the head commit (check, report or status). */
    RESULT_STATUS,
}

/**
 * The one failure for an operation the platform does not implement: thrown, or `Result.failure` of this for
 * `Result`-returning methods, never a silent success. [operation] is the SDK method name (e.g. `addReviewers`). It is
 * an [UnsupportedOperationException], so callers that already catch that keep working.
 */
public class UnsupportedPlatformOperationException(
    public val operation: String,
) : UnsupportedOperationException("$operation is not supported by this platform")

/**
 * [block]'s answer, or `null` when it throws [UnsupportedPlatformOperationException]: for a caller of an optional query
 * method that treats "not supported" like "cannot tell".
 */
public inline fun <T> nullIfUnsupported(block: () -> T): T? =
    try {
        block()
    } catch (_: UnsupportedPlatformOperationException) {
        null
    }

/** The one warning a caller logs when [platform] does not support [operation]: the same text everywhere. */
public fun Logger.warnUnsupported(
    platform: String,
    operation: String,
) {
    warn("$operation skipped: not supported by $platform")
}
