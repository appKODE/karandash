package io.karandash.plugins.ai

import io.karandash.plugins.ai.core.incremental.IncrementalContext
import io.karandash.plugins.ai.dispatch.SkippedFiles

/** `ai.incremental`: the provenance line that says which changed files this run re-reviewed and which it skipped. */
internal object IncrementalSummary {
    const val RULE = "ai-incremental"
    private const val SHORT_SHA = 8

    /**
     * The "How this review was made" line, or `null` when nothing was skipped as unchanged (first run, or every file
     * changed). A note when a stored snapshot was ignored ([IncrementalContext.fullReviewReason]). [reviewed] is how
     * many changed files an AI rule reviewed, once the review ran; null before it. [notSent] adds the files the
     * preprocessing left out of at least one rule's diff.
     */
    fun note(
        context: IncrementalContext,
        notSent: SkippedFiles = SkippedFiles(),
        reviewed: Int? = null,
    ): String? {
        val changed = context.changedFiles.size
        val reviewedText = reviewed?.let { ", $it reviewed" } ?: " to review"
        context.fullReviewReason?.let { reason ->
            return "Incremental AI review: $changed changed file(s)$reviewedText, $reason"
        }
        val skipped = context.unchangedFiles
        if (skipped.isEmpty()) return null
        val since = context.lastReviewedSha?.let { " since ${it.take(SHORT_SHA)}" }.orEmpty()
        val notSentText = listOf(
            "too large" to notSent.tooLarge.size,
            "filtered" to notSent.filtered.size,
            "binary" to notSent.binary.size,
        ).filter { it.second > 0 }
            .joinToString { "${it.second} ${it.first}" }
            .let { if (it.isEmpty()) "" else "; not sent to AI by at least one rule: $it" }
        return "Incremental AI review: $changed changed file(s)$reviewedText, ${skipped.size} unchanged$since " +
            "skipped (cached findings reused)$notSentText"
    }
}
