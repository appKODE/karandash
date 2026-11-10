package io.karandash.plugins.ai.core

import io.karandash.models.support.WellKnownMetadataKeys

/**
 * Metadata key constants for AI-generated [io.karandash.models.finding.Finding] instances.
 *
 * These keys are set by AI rule modules (e.g., MrSummaryGenerator, CodeSuggestionGenerator)
 * and consumed by [io.karandash.plugins.ai.core.pipeline.AiMetadataTransformer] to bake
 * AI-specific metadata into standard Finding fields before findings reach reporters.
 *
 * All constants delegate to [WellKnownMetadataKeys] which is the canonical source.
 */
object AiFindingMetadataKeys {
    /** AI-generated summary text, produced by `ai-mr-summary`. */
    val AI_SUMMARY = WellKnownMetadataKeys.AI_SUMMARY

    /** Sentinel flag marking a finding as an MR-level summary (produced by `ai-mr-summary`). */
    val IS_MR_SUMMARY = WellKnownMetadataKeys.IS_MR_SUMMARY

    /** Original code snippet for a suggested change, produced by `ai-code-suggestions`. */
    val EXISTING_CODE = WellKnownMetadataKeys.EXISTING_CODE

    /** Replacement code snippet for a suggested change, produced by `ai-code-suggestions`. */
    val SUGGESTED_CODE = WellKnownMetadataKeys.SUGGESTED_CODE

    /**
     * Category label for a finding (e.g., "correctness", "security", "improvement").
     * Produced by `ai-code-suggestions`, `ai-review-comments`, and `ai-review-thread-analysis`.
     */
    val CATEGORY = WellKnownMetadataKeys.CATEGORY

    /** AI model identifier used to generate a finding (e.g., "gpt-4o", "claude-3-5-sonnet"). */
    val AI_MODEL = WellKnownMetadataKeys.AI_MODEL

    /**
     * Provider id that served the finding (e.g., "openai"). Plain string key; to be reconciled with the
     * typed presentation keys.
     */
    const val AI_PROVIDER = "aiProvider"

    /** The `.karandash/learnings.yaml` entry (its path glob, else its rule) that downgraded a finding. */
    const val LEARNING = "learning"

    /** Name of the one `ai.review.instructions` entry that matched the reviewed changes. */
    const val INSTRUCTION = "instruction"

    /** `"true"` when the finding was produced after a model fallback (response model differs from the requested one). */
    const val AI_FALLBACK_USED = "aiFallbackUsed"

    /** Where the system prompt came from: `semi-formal`, `configured prompt` or `analyzer default`. */
    const val AI_PROMPT_SOURCE = "aiPromptSource"

    /**
     * Set to `"true"` when an out-of-diff line was demoted to a file-level location instead of dropping
     * the finding; the value of [LLM_LINE] holds the line the model reported.
     */
    const val LINE_DEMOTED = WellKnownMetadataKeys.LINE_DEMOTED

    /** Line number the model reported before the location was demoted to file level. */
    const val LLM_LINE = "llmLine"

    /** The added line the model quoted as the finding's evidence; grounding checks it sits at the anchor. */
    const val EVIDENCE = "evidence"

    /** Local grounding verdict of an AI file finding: `grounded`, `demoted` or `refuted`. */
    const val GROUNDING = "grounding"

    /** `agreed` when a second sample of the same analyzer reported the finding too; read by the cap ranking. */
    const val AGREEMENT = "agreement"

    /** `k/n`: how many of the n review-comments samples reported the finding; shown as the reporter tag. */
    const val SAMPLES = "samples"

    /** Cap ranking score ([io.karandash.plugins.ai.core.pipeline.FindingCap]), set when a cap had to cut. */
    const val SCORE = "score"

    /** Why a precision stage (grounding, ranking, dedup) demoted or merged a finding, with its numbers. */
    const val DECISION_REASON = "decisionReason"

    /** Comma-separated rule ids whose same-place findings in-run dedup merged into this one. */
    const val MERGED_RULES = "mergedRules"

    /** Comma-separated lines of same-file findings with the same message that dedup collapsed into this one. */
    const val MERGED_LINES = "mergedLines"

    /** Total tokens consumed by the AI call that produced a finding. */
    val AI_TOKENS_USED = WellKnownMetadataKeys.AI_TOKENS_USED

    /** Duration in milliseconds for the AI call that produced a finding. */
    val AI_DURATION_MS = WellKnownMetadataKeys.AI_DURATION_MS

    /** Confidence score (0.0–1.0) for a finding, produced by analyzers that support it. */
    val CONFIDENCE = WellKnownMetadataKeys.CONFIDENCE

    /** Comma-separated rule ids whose findings at the same location `ai.correlation` merged into this one. */
    const val CORRELATED_SOURCES = "correlatedSources"

    /** Sentinel flag marking a finding as a risk advisory (produced by `ai-risk-scoring`). */
    val IS_RISK_ADVISORY = WellKnownMetadataKeys.IS_RISK_ADVISORY

    /** Sentinel flag marking a finding as a fix suggestion (produced by `ai-fix-suggestions`). */
    val IS_FIX_SUGGESTION = WellKnownMetadataKeys.IS_FIX_SUGGESTION

    /** Sentinel flag marking a finding as label categorization (produced by `ai-change-categorization`). */
    val IS_CATEGORIZATION = WellKnownMetadataKeys.IS_CATEGORIZATION

    /** Sentinel flag marking a finding as description quality (produced by `ai-description-quality`). */
    val IS_DESCRIPTION_QUALITY = WellKnownMetadataKeys.IS_DESCRIPTION_QUALITY

    /** Sentinel flag marking a finding as a PR split recommendation (produced by `ai-pr-split`). */
    val IS_PR_SPLIT = WellKnownMetadataKeys.IS_PR_SPLIT

    /** Sentinel flag marking a finding as ticket compliance (set by a plugin ticket-compliance analyzer; the built-in one was removed). */
    val IS_TICKET_COMPLIANCE = WellKnownMetadataKeys.IS_TICKET_COMPLIANCE

    /** Sentinel flag marking a finding as a generated description (produced by `ai-generate-description`). */
    val IS_GENERATED_DESCRIPTION = WellKnownMetadataKeys.IS_GENERATED_DESCRIPTION

    /** Risk score (0.0–1.0), produced by `ai-risk-scoring`. */
    val RISK_SCORE = WellKnownMetadataKeys.RISK_SCORE

    /** Measured process risk composite (0.0–1.0), shared by the triage analyzers; see [TriageRisk]. */
    val PROCESS_RISK = WellKnownMetadataKeys.PROCESS_RISK

    /** Risk level label (e.g., "low", "medium", "high"), produced by `ai-risk-scoring`. */
    val RISK_LEVEL = WellKnownMetadataKeys.RISK_LEVEL

    /** Semicolon-separated risk factors explaining the risk score, produced by `ai-risk-scoring`. */
    val RISK_FACTORS = WellKnownMetadataKeys.RISK_FACTORS

    /** AI-suggested MR title, produced by `ai-generate-description`. */
    val SUGGESTED_TITLE = WellKnownMetadataKeys.SUGGESTED_TITLE

    /** AI-suggested MR description body, produced by `ai-generate-description`. */
    val SUGGESTED_DESCRIPTION = WellKnownMetadataKeys.SUGGESTED_DESCRIPTION

    /** Quality score (0.0–1.0) for the MR description, produced by `ai-description-quality`. */
    val QUALITY_SCORE = WellKnownMetadataKeys.QUALITY_SCORE

    /** Number of quality issues found, produced by `ai-description-quality`. */
    val ISSUE_COUNT = WellKnownMetadataKeys.ISSUE_COUNT

    /** Whether the MR should be split into smaller PRs, produced by `ai-pr-split`. */
    val SHOULD_SPLIT = WellKnownMetadataKeys.SHOULD_SPLIT

    /** Number of suggested splits, produced by `ai-pr-split`. */
    val SPLIT_COUNT = WellKnownMetadataKeys.SPLIT_COUNT

    /** JSON-encoded list of related patterns, produced by `ai-similar-issues`. */
    val RELATED_PATTERNS = WellKnownMetadataKeys.RELATED_PATTERNS

    /** Comma-separated search terms, produced by `ai-similar-issues`. */
    val SEARCH_TERMS = WellKnownMetadataKeys.SEARCH_TERMS

    /** Quality dimension label (e.g., "correctness", "readability"), produced by `ai-code-quality`. */
    val DIMENSION = WellKnownMetadataKeys.DIMENSION

    /** Name of the person who introduced a bug, produced by `ai-ownership-analysis`. */
    val BUG_INTRODUCED_BY = WellKnownMetadataKeys.BUG_INTRODUCED_BY

    /** Name of the person who fixed a bug, produced by `ai-ownership-analysis`. */
    val FIXED_BY = WellKnownMetadataKeys.FIXED_BY

    /** File path associated with a bug-fix correlation, produced by `ai-ownership-analysis`. */
    val FILE = WellKnownMetadataKeys.FILE

    /** Human-readable fix suggestion text, produced by `ai-fix-suggestions`. */
    val SUGGESTED_FIX = WellKnownMetadataKeys.SUGGESTED_FIX

    /** Rule ID of the original finding that triggered a fix suggestion, produced by `ai-fix-suggestions`. */
    val ORIGINAL_RULE = WellKnownMetadataKeys.ORIGINAL_RULE

    /** Message of the original finding that triggered a fix suggestion, produced by `ai-fix-suggestions`. */
    val ORIGINAL_MESSAGE = WellKnownMetadataKeys.ORIGINAL_MESSAGE

    /** Ticket compliance score (0.0–1.0 or "null"), set by a plugin ticket-compliance analyzer. */
    val COMPLIANCE_SCORE = WellKnownMetadataKeys.COMPLIANCE_SCORE

    /** Semicolon-separated fulfilled requirements, set by a plugin ticket-compliance analyzer. */
    val FULFILLED_REQUIREMENTS = WellKnownMetadataKeys.FULFILLED_REQUIREMENTS

    /** Semicolon-separated missing requirements, set by a plugin ticket-compliance analyzer. */
    val MISSING_REQUIREMENTS = WellKnownMetadataKeys.MISSING_REQUIREMENTS

    /** Semicolon-separated partial requirements, set by a plugin ticket-compliance analyzer. */
    val PARTIAL_REQUIREMENTS = WellKnownMetadataKeys.PARTIAL_REQUIREMENTS

    /** Comma-separated list of suggested labels, produced by `ai-change-categorization`. */
    val SUGGESTED_LABELS = WellKnownMetadataKeys.SUGGESTED_LABELS

    /** AI reasoning text explaining a categorization decision, produced by `ai-change-categorization`. */
    val REASONING = WellKnownMetadataKeys.REASONING

    /**
     * Sentinel flag set by [io.karandash.plugins.ai.core.pipeline.AiMetadataTransformer] after a finding
     * has been fully transformed. Prevents duplicate diff blocks when the transformer is invoked
     * more than once on the same finding.
     */
    val AI_SUGGESTION_BAKED = WellKnownMetadataKeys.AI_SUGGESTION_BAKED

    /** Sentinel flag marking a finding as a design-doc drift warning (produced by `ai-design-doc-drift`). */
    const val IS_DESIGN_DOC_DRIFT = "isDesignDocDrift"

    /** Drift score (0.0–1.0) indicating how much implementation diverges from design, produced by `ai-design-doc-drift`. */
    const val DRIFT_SCORE = WellKnownMetadataKeys.DRIFT_SCORE

    /** Semicolon-separated design sections where implementation diverges, produced by `ai-design-doc-drift`. */
    const val DIVERGED_SECTIONS = "divergedSections"
}
