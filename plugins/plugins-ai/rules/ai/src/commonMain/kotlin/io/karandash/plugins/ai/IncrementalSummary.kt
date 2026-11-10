package io.karandash.plugins.ai

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.incremental.IncrementalContext
import io.karandash.plugins.ai.dispatch.SkippedFiles

/** `ai.incremental`: the INFO finding that says which files this run re-reviewed and which it skipped. */
internal object IncrementalSummary {
    const val RULE = "ai-incremental"
    private const val SHORT_SHA = 8
    private const val LISTED_FILES = 20

    /**
     * `null` when nothing was skipped as unchanged (first run, or every file changed); a note when a stored snapshot
     * was ignored ([IncrementalContext.fullReviewReason]). [notSent] adds the
     * files the preprocessing left out of at least one rule's diff.
     */
    fun finding(
        context: IncrementalContext,
        notSent: SkippedFiles = SkippedFiles(),
    ): Finding? {
        context.fullReviewReason?.let { reason ->
            val message = "Incremental AI review: all ${context.changedFiles.size} file(s) reviewed, $reason"
            return Finding(severity = Severity.INFO, message = message, rule = RULE)
        }
        val skipped = context.unchangedFiles
        if (skipped.isEmpty()) return null
        val since = context.lastReviewedSha?.let { " since ${it.take(SHORT_SHA)}" }.orEmpty()
        val listed = skipped.take(LISTED_FILES).joinToString("\n") { "- $it" }
        val more = if (skipped.size > LISTED_FILES) "\n- … and ${skipped.size - LISTED_FILES} more" else ""
        val counts = mapOf(
            "tooLargeFiles" to notSent.tooLarge.size,
            "filteredFiles" to notSent.filtered.size,
            "binaryFiles" to notSent.binary.size,
        )
        val notSentText = listOf(
            "too large" to notSent.tooLarge.size,
            "filtered" to notSent.filtered.size,
            "binary" to notSent.binary.size,
        ).filter { it.second > 0 }
            .joinToString { "${it.second} ${it.first}" }
            .let { if (it.isEmpty()) "" else "; not sent to AI by at least one rule: $it" }
        return Finding(
            severity = Severity.INFO,
            message = "Incremental AI review: ${context.changedFiles.size} file(s) reviewed, " +
                "${skipped.size} unchanged$since skipped (cached findings reused)$notSentText",
            rule = RULE,
            explanation = "Skipped (unchanged) files:\n$listed$more",
            metadata = mapOf(
                "reviewedFiles" to context.changedFiles.size.toString(),
                "skippedFiles" to skipped.size.toString(),
            ) + counts.mapValues { it.value.toString() },
        )
    }
}
