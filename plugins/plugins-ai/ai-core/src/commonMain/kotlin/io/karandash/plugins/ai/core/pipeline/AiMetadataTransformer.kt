package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.isAiFinding

/**
 * Pure pre-reporter pipeline stage that bakes AI-specific finding metadata into
 * standard [Finding] fields so all reporters benefit without any AI-specific knowledge.
 *
 * Handles the following metadata keys produced by AI rule modules:
 * - [AiFindingMetadataKeys.AI_SUMMARY] - appended to [Finding.message]
 * - `suggestedTitle` - formatted into [Finding.suggestion]
 * - `suggestedDescription` - formatted into [Finding.suggestion]
 * - [AiFindingMetadataKeys.EXISTING_CODE] + [AiFindingMetadataKeys.SUGGESTED_CODE] - merged from
 *   a paired suggestion finding reported at the same place ([reportedAt]) into its review finding, which
 *   takes the suggestion's location, then rendered as a markdown diff block in [Finding.suggestion].
 *   The review's message and category are left unchanged: a suggestion's `readability` must not demote a `BUG`. A multi-line existing snippet widens the location to its
 *   last line (`endLine`). Unmatched suggestion findings are kept on their own.
 *
 * Findings with none of the handled metadata keys are returned unchanged (same instance).
 */
object AiMetadataTransformer {
    /** Transforms all findings in [findings], returning a new list only if any finding changed. */
    fun transform(findings: List<Finding>): List<Finding> {
        val merged = mergeSuggestionFindings(findings)
        var anyChanged = merged !== findings
        val result = merged.map { finding ->
            val transformed = transformAiFinding(finding)
            if (transformed !== finding) anyChanged = true
            transformed
        }
        return if (anyChanged) result else findings
    }

    /**
     * Merges suggestion findings (those carrying [AiFindingMetadataKeys.EXISTING_CODE] +
     * [AiFindingMetadataKeys.SUGGESTED_CODE]) into their paired AI review finding reported at the same
     * line; the review moves to the suggestion's location, which AnchorResolver fitted to the existing
     * code. Each suggestion pairs with one review at most; a suggestion left unpaired
     * (`codeSuggestions.mode: separate`, or a second one at that line) is kept on its own.
     */
    private fun mergeSuggestionFindings(findings: List<Finding>): List<Finding> {
        val (suggestions, others) = findings.partition { f ->
            !f.metadata[AiFindingMetadataKeys.EXISTING_CODE].isNullOrBlank() &&
                !f.metadata[AiFindingMetadataKeys.SUGGESTED_CODE].isNullOrBlank() &&
                f.metadata[AiFindingMetadataKeys.AI_SUGGESTION_BAKED] != "true"
        }

        if (suggestions.isEmpty()) return findings

        val unpaired = suggestions.toMutableList()
        // review-comments pair first, so a suggestion joins the comment on its line rather than a code-quality finding.
        val matches = others
            .withIndex()
            .filter { it.value.isAiFinding() }
            .sortedBy { if (it.value.rule == AiRuleIds.REVIEW_COMMENTS) 0 else 1 }
            .mapNotNull { (index, other) ->
                unpaired.find { pairsWith(it, other) }?.let { match ->
                    unpaired.remove(match)
                    index to match
                }
            }.toMap()
        val merged = others.mapIndexed { index, other ->
            val match = matches[index]
            if (match != null) {
                val extraMeta = buildMap {
                    match.metadata[AiFindingMetadataKeys.EXISTING_CODE]?.let {
                        put(AiFindingMetadataKeys.EXISTING_CODE, it)
                    }
                    match.metadata[AiFindingMetadataKeys.SUGGESTED_CODE]?.let {
                        put(AiFindingMetadataKeys.SUGGESTED_CODE, it)
                    }
                }
                other.copy(location = match.location, metadata = other.metadata + extraMeta)
            } else {
                other
            }
        }
        return merged + unpaired
    }

    /**
     * Where the model reported [finding]: its location with the line set to [AiFindingMetadataKeys.LLM_LINE]
     * when AnchorResolver moved or demoted it. A review comment and its companion suggestion share it even after
     * one moved.
     */
    fun reportedAt(finding: Finding): Location? {
        val ref = finding.location as? Location.FileRef ?: return finding.location
        val llmLine = finding.metadata[AiFindingMetadataKeys.LLM_LINE]?.toIntOrNull() ?: return ref
        return ref.copy(line = llmLine, endLine = ref.endLine?.takeIf { it >= llmLine })
    }

    /** True when [a] and [b] were reported at the same file and line, whatever their end lines. */
    fun pairsWith(
        a: Finding,
        b: Finding,
    ): Boolean = startOf(reportedAt(a)) == startOf(reportedAt(b))

    private fun startOf(location: Location?): Location? = (location as? Location.FileRef)?.copy(endLine = null) ?: location

    private fun transformAiFinding(finding: Finding): Finding {
        val meta = finding.metadata
        if (meta[AiFindingMetadataKeys.AI_SUGGESTION_BAKED] == "true") return finding

        var message = finding.message
        var suggestion = finding.suggestion

        val aiSummary = meta[AiFindingMetadataKeys.AI_SUMMARY]
        if (!aiSummary.isNullOrBlank() && !message.contains(aiSummary)) {
            message = "$message\n\n$aiSummary"
        }

        val suggestedTitle = meta[AiFindingMetadataKeys.SUGGESTED_TITLE]
        if (!suggestedTitle.isNullOrBlank()) {
            val part = "Suggested title: `$suggestedTitle`"
            suggestion = if (suggestion.isNullOrBlank()) part else "$suggestion\n\n$part"
        }

        val suggestedDescription = meta[AiFindingMetadataKeys.SUGGESTED_DESCRIPTION]
        if (!suggestedDescription.isNullOrBlank()) {
            val part = "Suggested description:\n\n$suggestedDescription"
            suggestion = if (suggestion.isNullOrBlank()) part else "$suggestion\n\n$part"
        }

        val existingCode = meta[AiFindingMetadataKeys.EXISTING_CODE]
        val suggestedCode = meta[AiFindingMetadataKeys.SUGGESTED_CODE]
        if (!existingCode.isNullOrBlank() && !suggestedCode.isNullOrBlank()) {
            val category = meta[AiFindingMetadataKeys.CATEGORY] ?: "improvement"
            val diff = buildString {
                appendLine("Suggested change ($category):")
                appendLine("```diff")
                existingCode.lines().forEach { appendLine("- $it") }
                suggestedCode.lines().forEach { appendLine("+ $it") }
                append("```")
            }
            suggestion = if (suggestion.isNullOrBlank()) diff else "$suggestion\n\n$diff"
        }

        if (message == finding.message && suggestion == finding.suggestion) return finding

        return finding.copy(
            message = message,
            suggestion = suggestion,
            location = suggestionRange(finding.location, existingCode, suggestedCode),
            metadata = meta + (AiFindingMetadataKeys.AI_SUGGESTION_BAKED to "true"),
        )
    }

    /**
     * Widens a single-line anchor to every line [existingCode] covers, so the committable
     * suggestion block replaces the whole snippet (GitHub `start_line`/`line`, GitLab
     * `suggestion:-N+0`) rather than only its first line.
     */
    private fun suggestionRange(
        location: Location?,
        existingCode: String?,
        suggestedCode: String?,
    ): Location? {
        if (existingCode.isNullOrBlank() || suggestedCode.isNullOrBlank()) return location
        val ref = location as? Location.FileRef ?: return location
        val line = ref.line ?: return location
        val span = existingCode.trimEnd('\n').lines().size
        return if (ref.endLine != null || span < 2) location else ref.copy(endLine = line + span - 1)
    }
}
