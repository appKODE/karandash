package io.karandash.models

import kotlinx.serialization.Serializable

/**
 * The method through which an approval was expressed.
 *
 * Platform adapters populate [CODE_REVIEW]; [COMMENT_KEYWORD] and [EMOJI_REACTION] come from
 * `PlatformServiceModule.fetchApprovalSignals` and count when the approval rule's `methods` list them.
 */
@Serializable
enum class ApprovalMethod {
    /** Standard platform approval via the review/approve action. */
    CODE_REVIEW,

    /** Approval expressed via a keyword in MR comments (e.g., "LGTM", "Approved"). */
    COMMENT_KEYWORD,

    /** Approval expressed via an emoji reaction on the MR. */
    EMOJI_REACTION,
}
