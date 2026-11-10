package io.karandash.plugins.ai.core.rules

import kotlinx.serialization.Serializable

// Per-analyzer configuration groups for AI rule limits, thresholds, and parameters.
// Each data class groups the settings consumed by one analyzer (or a small family
// of related analyzers). Defaults match the former flat constants in AiRuleConfig.

/**
 * Configuration for the risk-scoring analyzer. [errorEnabled] is true only when `thresholds.riskError` is
 * set: risk is advisory, so a score past [errorThreshold] stays a WARNING unless the project opted in.
 */
@Serializable
data class RiskScoringLimits(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val errorThreshold: Double = DEFAULT_ERROR_THRESHOLD,
    val warningThreshold: Double = DEFAULT_WARNING_THRESHOLD,
    val includeDiff: Boolean = DEFAULT_INCLUDE_DIFF,
    val errorEnabled: Boolean = false,
    /** `ai.limits.riskScoringMaxDiffChars`: cap of the diff risk scoring sends when `riskScoringIncludeDiff` is on; null leaves the tier plan cap. */
    val maxDiffChars: Int? = null,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 800
        const val DEFAULT_TEMPERATURE = 0.1
        const val DEFAULT_ERROR_THRESHOLD = 0.7
        const val DEFAULT_WARNING_THRESHOLD = 0.4
        const val DEFAULT_INCLUDE_DIFF = false
    }
}

/** Configuration for the change-categorization analyzer. */
@Serializable
data class CategorizerLimits(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 500
        const val DEFAULT_TEMPERATURE = 0.2
    }
}

/**
 * Configuration for analyzers that share review-style parameters:
 * review-comments, pr-split, outdated-docs,
 * mr-summary, and generate-description.
 */
@Serializable
data class ReviewLimits(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    /** Soft cap on review comments per MR: BUG/SEC/DATA/LEAK comments go past it, the rest fill what is left. */
    val maxComments: Int = DEFAULT_MAX_COMMENTS,
    /** Hard cap on review comments per MR, protected categories included, worst and surest kept; null is no cap. */
    val maxCommentsHard: Int? = null,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 8000
        const val DEFAULT_TEMPERATURE = 0.1
        const val DEFAULT_MAX_COMMENTS = 10
    }
}

/** Configuration for description-quality and review-thread-analysis analyzers. */
@Serializable
data class DescriptionLimits(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 800
        const val DEFAULT_TEMPERATURE = 0.2
    }
}

/** Configuration for the fix-suggestions analyzer and inline suggestion controls. */
@Serializable
data class FixSuggestionLimits(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val includeFixSuggestions: Boolean = DEFAULT_INCLUDE_FIX_SUGGESTIONS,
    val minSeverity: String = DEFAULT_MIN_SEVERITY,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 1500
        const val DEFAULT_TEMPERATURE = 0.2
        const val DEFAULT_INCLUDE_FIX_SUGGESTIONS = true
        const val DEFAULT_MIN_SEVERITY = "warning"
    }
}

/** Configuration for the similar-issues analyzer. */
@Serializable
data class SimilarIssueLimits(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val maxDisplayPatterns: Int = DEFAULT_MAX_DISPLAY_PATTERNS,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 1000
        const val DEFAULT_TEMPERATURE = 0.2
        const val DEFAULT_MAX_DISPLAY_PATTERNS = 3
    }
}

/** Configuration for the code-suggestions analyzer. */
@Serializable
data class CodeSuggestionLimits(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val mode: String = DEFAULT_MODE,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 2000
        const val DEFAULT_TEMPERATURE = 0.2
        const val DEFAULT_MODE = "inline"
    }
}

/** Configuration for the PR-split analyzer. */
@Serializable
data class SplitLimits(
    val threshold: Int = DEFAULT_THRESHOLD,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 15
    }
}

/** Configuration for the code-quality analyzer. */
@Serializable
data class CodeQualityLimits(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val warningThreshold: Double = DEFAULT_WARNING_THRESHOLD,
    val errorThreshold: Double = DEFAULT_ERROR_THRESHOLD,
    val includeMaturityLevel: Boolean = DEFAULT_INCLUDE_MATURITY_LEVEL,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 2000
        const val DEFAULT_TEMPERATURE = 0.1
        const val DEFAULT_WARNING_THRESHOLD = 0.6
        const val DEFAULT_ERROR_THRESHOLD = 0.3
        const val DEFAULT_INCLUDE_MATURITY_LEVEL = false
    }
}

/** Configuration for the ownership analyzer. */
@Serializable
data class OwnershipLimits(
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val warningThreshold: Double = DEFAULT_WARNING_THRESHOLD,
    val errorThreshold: Double = DEFAULT_ERROR_THRESHOLD,
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 2000
        const val DEFAULT_TEMPERATURE = 0.1
        const val DEFAULT_WARNING_THRESHOLD = 0.7
        const val DEFAULT_ERROR_THRESHOLD = 0.4
    }
}
