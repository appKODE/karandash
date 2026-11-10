package io.karandash.models

import kotlinx.serialization.Serializable

/**
 * The state of a review or approval on a merge request.
 *
 * Platform adapters normalize vendor-specific state strings via [fromPlatformState].
 */
@Serializable
enum class ReviewState {
    APPROVED,
    CHANGES_REQUESTED,
    COMMENTED,
    DISMISSED,
    PENDING,
    ;

    companion object {
        /**
         * Normalizes a platform-specific review state string to a [ReviewState].
         *
         * Handles common variations across GitLab, GitHub, Bitbucket, Gerrit, and
         * Azure DevOps. Unknown states map to [PENDING].
         */
        fun fromPlatformState(state: String): ReviewState =
            when (state.lowercase()) {
                "approved", "approve" -> APPROVED
                "changes_requested", "request_changes", "request-changes" -> CHANGES_REQUESTED
                "commented", "comment" -> COMMENTED
                "dismissed", "dismiss" -> DISMISSED
                else -> PENDING
            }
    }
}
