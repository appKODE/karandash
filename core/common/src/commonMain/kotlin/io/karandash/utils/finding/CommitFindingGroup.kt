package io.karandash.utils.finding

import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding

/**
 * Folds the same finding raised on many commits of one rule into a single finding, so a 71-commit MR gets one
 * line instead of 71: `71 commits have no CHANGELOG line in the body (e379ea0, a1b2c3d, 9f0e1d2, +68 more)`.
 *
 * The first commit's finding gives the severity, suggestion, explanation and metadata (its concrete fix). Every
 * per-commit message is kept in a collapsible attachment. The fingerprint is built from the rule and the summary
 * only, so a new failing commit does not change it (and re-post the thread). Findings that carry a location keep
 * their inline anchor and are never folded.
 */
object CommitFindingGroup {
    /** Fewer loose per-commit findings than this stay separate. */
    const val COLLAPSE_AT = 3

    /** How many items [shortList] names before `+N more`. */
    const val SHOWN = 3

    /**
     * [perCommit] pairs each finding with its commit's short SHA, all of one kind; [summary] is the predicate after
     * the count, e.g. `have no CHANGELOG line in the body`.
     */
    fun collapse(
        perCommit: List<Pair<String, Finding>>,
        summary: String,
    ): List<Finding> {
        val (anchored, loose) = perCommit.partition { it.second.location != null }
        if (loose.size < COLLAPSE_AT) return perCommit.map { it.second }
        val first = loose.first().second
        val collapsed = first.copy(
            message = "${loose.size} commits $summary (${shortList(loose.map { it.first })})",
            fingerprint = FindingFingerprint.generate(first.rule, null, "commits $summary"),
            attachments = first.attachments +
                Attachment(
                    filename = "All ${loose.size} commits",
                    content = loose.joinToString("\n") { "- ${it.second.message}" },
                ),
        )
        return listOf(collapsed) + anchored.map { it.second }
    }

    /** `a, b, c, +5 more`: the first [SHOWN] of [items], then how many are left out. */
    fun shortList(items: Collection<String>): String {
        val shown = items.take(SHOWN).joinToString()
        return if (items.size > SHOWN) "$shown, +${items.size - SHOWN} more" else shown
    }
}
