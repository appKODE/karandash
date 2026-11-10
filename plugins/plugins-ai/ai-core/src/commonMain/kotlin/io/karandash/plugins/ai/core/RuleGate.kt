package io.karandash.plugins.ai.core

import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.research.BloomFilter
import io.karandash.sdk.regex.SafeRegex
import io.karandash.utils.GlobMatcher
import kotlinx.serialization.Serializable

/**
 * Pre-screens MRs to decide whether expensive AI rules should run.
 *
 * Uses cheap heuristics (file patterns, size, labels, branch patterns) to gate
 * AI execution. Saves token budget on MRs that are unlikely to need AI analysis.
 */
class RuleGate {
    /**
     * Evaluates whether a rule should run for the given MR.
     *
     * @param ruleId The rule identifier to look up in [gates].
     * @param mr The merge request to evaluate.
     * @param gates List of gate configurations.
     * @param onInvalidPattern Called with a warning when `skipIfTitleMatches` is not a valid, safe regex
     *   (the condition is then ignored).
     * @return null if the rule should run, or a skip reason string if gated out.
     */
    fun evaluate(
        ruleId: String,
        mr: MergeRequest,
        gates: List<GateConfig>,
        onInvalidPattern: (String) -> Unit = {},
    ): String? {
        val gate = gates.find { it.ruleId == ruleId } ?: return null

        if (gate.filePatterns.isNotEmpty()) {
            // Bloom pre-screen: build filter from file extensions in changed paths.
            // If a pattern requires a specific extension and no changed file has it, skip early.
            val extensions = mr.changes.mapNotNull { change ->
                val dot = change.path.lastIndexOf('.')
                if (dot >= 0) change.path.substring(dot) else null
            }
            if (extensions.isNotEmpty()) {
                val filter = BloomFilter.fromPatterns(extensions)
                val anyPossible = gate.filePatterns.any { pattern ->
                    val dot = pattern.lastIndexOf('.')
                    val ext = if (dot >= 0) pattern.substring(dot) else ""
                    ext.isEmpty() || ext.contains('*') || filter.mightContain(ext)
                }
                if (!anyPossible) return "no files match patterns (bloom pre-screen)"
            }
            val anyMatch = mr.changes.any { change ->
                gate.filePatterns.any { pattern -> GlobMatcher.matches(change.path, pattern) }
            }
            if (!anyMatch) return "no files match patterns: ${gate.filePatterns}"
        }

        if (gate.minChangedFiles > 0 && mr.changes.size < gate.minChangedFiles) {
            return "too few changed files (${mr.changes.size} < ${gate.minChangedFiles})"
        }

        if (gate.minChangedLines > 0) {
            val totalLines = mr.changes.sumOf { it.additions + it.deletions }
            if (totalLines < gate.minChangedLines) {
                return "too few changed lines ($totalLines < ${gate.minChangedLines})"
            }
        }

        if (gate.requireLabel != null && gate.requireLabel !in mr.labels) {
            return "missing required label: ${gate.requireLabel}"
        }

        if (gate.requiredLabels.isNotEmpty()) {
            val hasLabel = gate.requiredLabels.any { it in mr.labels }
            if (!hasLabel) return "missing required label: ${gate.requiredLabels}"
        }

        if (gate.skipLabels.isNotEmpty()) {
            val hasSkipLabel = gate.skipLabels.any { it in mr.labels }
            if (hasSkipLabel) return "skip label present"
        }

        if (gate.skipBranchPatterns.isNotEmpty()) {
            val matches = gate.skipBranchPatterns.any { pattern ->
                mr.sourceBranch.contains(pattern)
            }
            if (matches) return "branch matches skip pattern"
        }

        if (gate.skipIfTitleMatches != null) {
            val titleRegex = SafeRegex.compileOrNull(gate.skipIfTitleMatches)
            if (titleRegex == null) {
                onInvalidPattern("gate for $ruleId: skipIfTitleMatches '${gate.skipIfTitleMatches}' is not a valid regex; ignored")
            } else if (titleRegex.containsMatchIn(mr.title)) {
                return "title matches skip pattern: ${gate.skipIfTitleMatches}"
            }
        }

        return null
    }
}

/**
 * Configuration for a single rule gate.
 *
 * All conditions are AND-ed together: the rule is skipped only when every
 * specified condition evaluates to "skip".
 *
 * @param ruleId The AI rule ID this gate applies to.
 * @param filePatterns Glob patterns; rule runs only if at least one changed file matches.
 * @param minChangedFiles Minimum number of changed files required to run the rule.
 * @param minChangedLines Minimum total changed lines (additions + deletions) required.
 * @param requiredLabels Rule runs only if the MR has at least one of these labels.
 * @param skipLabels Rule is skipped if the MR has any of these labels.
 * @param skipBranchPatterns Rule is skipped if the source branch contains any of these strings.
 * @param requireLabel Rule runs only if the MR has this label (single-label form of [requiredLabels]).
 * @param skipIfTitleMatches Rule is skipped if the MR title matches this regex (compiled via [SafeRegex]).
 */
@Serializable
data class GateConfig(
    val ruleId: String,
    val filePatterns: List<String> = emptyList(),
    val minChangedFiles: Int = 0,
    val minChangedLines: Int = 0,
    val requiredLabels: List<String> = emptyList(),
    val skipLabels: List<String> = emptyList(),
    val skipBranchPatterns: List<String> = emptyList(),
    val requireLabel: String? = null,
    val skipIfTitleMatches: String? = null,
)
