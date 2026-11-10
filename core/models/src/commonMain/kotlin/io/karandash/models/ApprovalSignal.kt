package io.karandash.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A raw approval-like signal a platform found on a merge request: a comment ([ApprovalMethod.COMMENT_KEYWORD],
 * [text] is the comment body) or a reaction ([ApprovalMethod.EMOJI_REACTION], [text] is the emoji name).
 * [toApproval] turns the ones that match the configured keywords and emojis into counted [Approval]s.
 */
@Serializable
data class ApprovalSignal(
    val user: User,
    val method: ApprovalMethod,
    val text: String,
    @SerialName("signaled_at")
    val signaledAt: String? = null,
) {
    /** The approval this signal expresses, or null when [text] matches none of [keywords] / [emojis]. */
    fun toApproval(
        keywords: List<String> = DEFAULT_KEYWORDS,
        emojis: List<String> = DEFAULT_EMOJIS,
    ): Approval? {
        val matches = when (method) {
            ApprovalMethod.COMMENT_KEYWORD -> keywords.any { text.trim().trimEnd('.', '!').equals(it, ignoreCase = true) }
            ApprovalMethod.EMOJI_REACTION -> emojis.any { text.trim().trim(':').equals(it, ignoreCase = true) }
            ApprovalMethod.CODE_REVIEW -> false
        }
        return if (matches) Approval(user = user, approvedAt = signaledAt, method = method) else null
    }

    companion object {
        /** Comment bodies that count as an approval unless `approval.commentKeywords` says otherwise. */
        val DEFAULT_KEYWORDS: List<String> = listOf("LGTM", "approved")

        /** Reaction names that count as an approval unless `approval.approvalEmojis` says otherwise. */
        val DEFAULT_EMOJIS: List<String> = listOf("thumbsup", "+1")
    }
}
