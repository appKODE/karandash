package io.karandash.utils.validation

import io.karandash.models.MergeRequest
import io.karandash.models.ReviewState

/**
 * Provides memoized (lazy-computed, cached) views of [MergeRequest] data that are
 * frequently accessed across multiple validators in a single validation run.
 *
 * This avoids re-building the same collections on every validator call when validating
 * the same MR with many rules.
 *
 * Usage:
 * ```kotlin
 * val ctx = mr.toContext()
 * val missing = requiredReviewers.filter { it !in ctx.reviewerNames }
 * ```
 */
class MrValidationContext(
    val mr: MergeRequest
) {
    /** Usernames of all current reviewers, computed once. */
    val reviewerNames: Set<String> by lazy {
        mr.reviewers.map { it.username }.toSet()
    }

    /**
     * Usernames of everyone whose review is [ReviewState.APPROVED], computed once.
     * CHANGES_REQUESTED, COMMENTED, DISMISSED and PENDING reviews are not approvals.
     */
    val approverNames: Set<String> by lazy {
        mr.approvals
            .filter { it.state == ReviewState.APPROVED }
            .map { it.user.username }
            .toSet()
    }

    /** All MR labels as a set for O(1) membership testing, computed once. */
    val labelSet: Set<String> by lazy {
        mr.labels.toSet()
    }

    /** Paths of all changed files, computed once. */
    val changedPaths: Set<String> by lazy {
        mr.changes.map { it.path }.toSet()
    }

    /** Total lines changed (additions + deletions), computed once. */
    val totalLinesChanged: Int by lazy {
        mr.changes.sumOf { it.additions + it.deletions }
    }

    /** Whether the MR is considered a draft (either flag or WIP patterns). */
    val isDraft: Boolean get() = mr.draft
}

/** Convenience extension to create a [MrValidationContext] from a [MergeRequest]. */
fun MergeRequest.toContext(): MrValidationContext = MrValidationContext(this)
