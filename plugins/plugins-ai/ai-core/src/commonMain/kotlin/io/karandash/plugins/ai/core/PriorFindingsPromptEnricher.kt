package io.karandash.plugins.ai.core

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.config.AiPriorFindingsConfig

/**
 * Enriches AI prompts with previously reported findings for the rule currently under evaluation.
 *
 * When a file is re-analyzed after a change, the model otherwise has no memory of what was reported
 * last time and tends to emit identical or paraphrased findings. This enricher renders the prior
 * findings as already raised and tells the model not to repeat them: their inline discussions stay
 * open on the platform, so a repeat would only re-post the same finding.
 *
 * Prior findings that a reviewer dismissed or rejected (per [feedbackStore]) are left out of that
 * section; [dismissedPrompt] lists the dismissed patterns themselves as "do not repeat".
 *
 * @property priorByFile Findings keyed by file path for files in the current delta.
 * @property priorMrLevel MR-level findings persisted from the previous run.
 * @property config Feature flag and caps.
 * @property feedbackStore Dismissal history: filters the prior findings and feeds [dismissedPrompt].
 * @property maxDismissed Maximum number of dismissed examples [dismissedPrompt] renders.
 */
class PriorFindingsPromptEnricher(
    private val priorByFile: Map<String, List<Finding>>,
    private val priorMrLevel: List<Finding>,
    private val config: AiPriorFindingsConfig,
    private val feedbackStore: FeedbackStore = FeedbackStore(),
    private val maxDismissed: Int = DEFAULT_MAX_DISMISSED,
) {
    /**
     * Renders the prompt fragment for [ruleId], or null when the feature is disabled or there
     * are no prior findings to show.
     *
     * Only findings matching [ruleId] are rendered - prior `mr-summary` output is shown only to
     * the `mr-summary` rule, prior `review-comments` only to `review-comments`, etc.
     */
    fun enrichPrompt(ruleId: String): String? {
        if (!config.enabled) return null

        val filteredByFile = priorByFile
            .mapValues { (_, findings) -> feedbackStore.filterFindings(findings.filter { it.rule == ruleId }).kept }
            .filterValues { it.isNotEmpty() }
        val filteredMrLevel = feedbackStore.filterFindings(priorMrLevel.filter { it.rule == ruleId }).kept
        if (filteredByFile.isEmpty() && filteredMrLevel.isEmpty()) return null

        return buildString {
            appendLine()
            appendLine("## Previously Reported Findings (already raised or rejected, do not repeat)")
            appendLine()
            appendLine(
                "These findings were already raised in a prior review; their discussions stay open " +
                    "until fixed. Do NOT repeat or paraphrase them, even when the issue is still " +
                    "present. Report ONLY new issues that exist in the current code."
            )
            for ((path, findings) in filteredByFile) {
                appendLine()
                appendLine("### $path")
                appendPriorSection(findings, config.maxPerFile)
            }
            if (filteredMrLevel.isNotEmpty()) {
                appendLine()
                appendLine("### MR-level")
                appendPriorSection(filteredMrLevel, config.maxForMrLevel)
            }
        }
    }

    /**
     * Renders the findings reviewers dismissed for [ruleId] (most-dismissed first, at most
     * [maxDismissed]) as patterns not to repeat, or null when there are none.
     */
    fun dismissedPrompt(ruleId: String): String? {
        val top = feedbackStore
            .dismissedForRule(ruleId)
            .sortedByDescending { it.dismissCount }
            .take(maxDismissed)
        if (top.isEmpty()) return null
        return buildString {
            appendLine()
            appendLine("## Previously Dismissed Findings (DO NOT repeat these patterns)")
            appendLine()
            appendLine("The following types of findings have been dismissed by reviewers.")
            appendLine("Avoid generating similar findings:")
            appendLine()
            for ((i, entry) in top.withIndex()) {
                appendLine("${i + 1}. [${entry.rule}] ${entry.messagePrefix}")
                entry.filePath?.let { appendLine("   File pattern: $it") }
                if (entry.dismissCount > 1) appendLine("   (dismissed ${entry.dismissCount} times)")
            }
        }
    }

    private fun StringBuilder.appendPriorSection(
        findings: List<Finding>,
        cap: Int,
    ) {
        val sorted = findings.sortedWith(PRIOR_FINDING_ORDER)
        val visible = if (sorted.size > cap) sorted.take(cap) else sorted
        for (finding in visible) {
            appendLine(formatPriorFinding(finding))
        }
        val dropped = sorted.size - visible.size
        if (dropped > 0) {
            appendLine("- ... and $dropped more finding(s) omitted by max cap")
        }
    }

    private fun formatPriorFinding(finding: Finding): String {
        val loc = finding.location
        val locationLabel = when (loc) {
            is Location.FileRef -> loc.line?.let { "line $it" } ?: "file"
            is Location.CommitRef -> "commit ${loc.sha.take(COMMIT_SHA_DISPLAY_LENGTH)}"
            null -> null
        }
        val prefix = listOfNotNull(locationLabel, "[${finding.severity.label()}]")
            .joinToString(" ")
        return "- $prefix: ${finding.message.trim().take(MESSAGE_PREVIEW_LIMIT)}"
    }

    companion object {
        /** Default maximum number of dismissed examples in [dismissedPrompt]. */
        const val DEFAULT_MAX_DISMISSED = 5
        private const val COMMIT_SHA_DISPLAY_LENGTH = 7
        private const val MESSAGE_PREVIEW_LIMIT = 500

        /** Display label for a severity - matches what most analyzers use in-output. */
        private fun Severity.label(): String =
            when (this) {
                Severity.ERROR -> "ERROR"
                Severity.WARNING -> "WARNING"
                Severity.INFO -> "INFO"
            }

        /** Sort higher-severity / higher-confidence findings first so the cap keeps the most important. */
        private val PRIOR_FINDING_ORDER: Comparator<Finding> = compareByDescending<Finding> { finding ->
            when (finding.severity) {
                Severity.ERROR -> 3
                Severity.WARNING -> 2
                Severity.INFO -> 1
            }
        }.thenByDescending { finding ->
            finding.confidence ?: 0f
        }
    }
}
