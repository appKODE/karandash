package io.karandash.plugins.ai.core

/**
 * AI analyzer rule IDs referenced outside their own analyzer module. The analyzer modules use these
 * constants as their IDs, so each ID string is written exactly once.
 */
object AiRuleIds {
    /** `CodeSuggestionsAnalyzerModule`; review-comments also emits it in `code-suggestions.mode: inline`. */
    const val CODE_SUGGESTIONS = "code-suggestions"

    /** Pre-merge checklist (`ai.checks`, `ai.checks-ticket-compliance`); run by the dispatcher, not an analyzer. */
    const val CHECKS = "pre-merge-checks"

    /** `RiskScoringAnalyzerModule`. */
    const val RISK_SCORING = "risk-scoring"

    /** `ReviewCommentsAnalyzerModule`. */
    const val REVIEW_COMMENTS = "review-comments"

    /** `FixSuggestionsAnalyzerModule`: AI fixes for the ERROR/WARNING findings of the run, deterministic ones included. */
    const val FIX_SUGGESTIONS = "fix-suggestions"

    /** `CodeQualityAnalyzerModule`: a quality score and quality findings. */
    const val CODE_QUALITY = "code-quality"

    /**
     * the suggestion and quality producers that run next to [REVIEW_COMMENTS]. Without an explicit
     * `ai.<analyzer>.max-tokens` each gets its own estimate as budget, outside the `ai.max-token-budget` split, so
     * enabling one does not shrink the other analyzers' shares.
     */
    val SEPARATE_PRODUCERS: Set<String> = setOf(CODE_SUGGESTIONS, CODE_QUALITY, FIX_SUGGESTIONS)
}
