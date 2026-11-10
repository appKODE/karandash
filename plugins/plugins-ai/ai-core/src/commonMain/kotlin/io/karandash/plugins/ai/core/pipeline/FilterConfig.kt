package io.karandash.plugins.ai.core.pipeline

import kotlinx.serialization.Serializable

/**
 * Configuration for the post-validation AI finding filter pipeline.
 *
 * Controls deduplication, confidence thresholds, and per-rule/per-file
 * caps to reduce noise from AI-generated findings before reporting.
 *
 * Presence of the `filter:` block in config enables the filter.
 * Omitting the block disables it - no explicit `enabled` flag needed.
 *
 * @param maxPerRule Maximum findings per rule. Cut by [FindingCap]: BUG/SEC/DATA/LEAK findings are never dropped.
 * @param maxPerFile Maximum findings per file. Cut by [FindingCap]: BUG/SEC/DATA/LEAK findings are never dropped.
 * @param minConfidence Minimum confidence threshold for AI findings. Findings below this are removed.
 * @param minConfidenceByModel Per-model override of [minConfidence]: model glob (a provider prefix plus a star) or exact id to threshold.
 *   The first matching entry wins; findings of other models (or without a model) use [minConfidence]. Models that
 *   report low confidence (Claude: 0.4-0.55) can get a lower bar than the global one.
 * @param deduplicate When true, removes duplicate findings with the same rule + file + line (keeps highest severity).
 * @param attemptJsonRecovery When false, a truncated JSON response is discarded instead of being closed and parsed.
 * @param validateDiffLines When false, findings on lines outside the diff are not checked.
 * @param downgradeInvalidLines When true, findings on lines outside the diff are downgraded to INFO instead of dropped.
 * @param anchored When false, findings on files outside the MR diff are not checked (evidence anchoring off).
 * @param requireFileInDiff When false, anchoring downgrades findings on files outside the diff to INFO instead of dropping them.
 * @param confidenceMetadata When false, the confidence score is stripped from finding metadata after filtering.
 * @param deduplicateKey Grouping key for [deduplicate]: [DEDUP_KEY_RULE_FILE_LINE], [DEDUP_KEY_RULE_FILE],
 *   or null for the semantic fingerprint (rule + location + message).
 * @param dropHedged When true (default, applies even without a `filter:` block), an AI file finding whose message
 *   hedges ("consider", "might", "возможно", ...) without naming a concrete failure at a line is dropped.
 * @param hedgeExemptConfidence A finding whose confidence is at least this is kept even when it hedges
 *   ("potentially"): the model is sure, the word is style. A value above 1.0 turns the exemption off.
 * @param dedupOpenThreads When true (default, applies even without a `filter:` block), an AI file finding that an
 *   unresolved discussion by someone else already covers (same file, within 3 lines, similar text) is dropped;
 *   see [FindingDuplicates]. [deduplicate] false also turns off the same-line repeat check of that class.
 */
@Serializable
data class FilterConfig(
    val maxPerRule: Int = DEFAULT_MAX_PER_RULE,
    val maxPerFile: Int = DEFAULT_MAX_PER_FILE,
    val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    val minConfidenceByModel: Map<String, Double> = emptyMap(),
    val deduplicate: Boolean = true,
    val attemptJsonRecovery: Boolean = true,
    val validateDiffLines: Boolean = true,
    val downgradeInvalidLines: Boolean = false,
    val anchored: Boolean = true,
    val requireFileInDiff: Boolean = true,
    val confidenceMetadata: Boolean = true,
    val deduplicateKey: String? = null,
    val dropHedged: Boolean = true,
    val hedgeExemptConfidence: Double = DEFAULT_HEDGE_EXEMPT_CONFIDENCE,
    val dedupOpenThreads: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_PER_RULE = 10
        const val DEFAULT_MAX_PER_FILE = 5
        const val DEFAULT_MIN_CONFIDENCE = 0.3
        const val DEFAULT_HEDGE_EXEMPT_CONFIDENCE = 0.7
        const val DEDUP_KEY_RULE_FILE_LINE = "rule:file:line"
        const val DEDUP_KEY_RULE_FILE = "rule:file"
    }
}
