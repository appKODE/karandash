package io.karandash.models.support

/**
 * Well-known metadata keys used in [Finding.metadata].
 *
 * Centralises magic strings so that producers (rule modules) and consumers
 * (reporters, auto-fixer) reference the same constants.
 */
object WellKnownMetadataKeys {
    /** Marks the finding as an MR summary (used by ConsoleReporterModule). */
    const val IS_MR_SUMMARY = "isMrSummary"

    /** AI-generated summary text, produced by `ai-mr-summary`. */
    const val AI_SUMMARY = "aiSummary"

    /** Original code snippet for a suggested change, produced by `ai-code-suggestions`. */
    const val EXISTING_CODE = "existingCode"

    /** Replacement code snippet for a suggested change, produced by `ai-code-suggestions`. */
    const val SUGGESTED_CODE = "suggestedCode"

    /**
     * Category label for a finding (e.g., "correctness", "security", "improvement").
     * Produced by `ai-code-suggestions`, `ai-review-comments`, and `ai-review-thread-analysis`.
     */
    const val CATEGORY = "category"

    /** AI model identifier used to generate a finding (e.g., "gpt-4o", "claude-3-5-sonnet"). */
    const val AI_MODEL = "aiModel"

    /** Total tokens consumed by the AI call that produced a finding. */
    const val AI_TOKENS_USED = "aiTokensUsed"

    /** Duration in milliseconds for the AI call that produced a finding. */
    const val AI_DURATION_MS = "aiDurationMs"

    /** The severity a finding had before a severity reclassification changed it (e.g. "ERROR"). */
    const val RECLASSIFIED_FROM = "reclassifiedFrom"

    /** Confidence score (0.0–1.0) for a finding, produced by analyzers that support it. */
    const val CONFIDENCE = "confidence"

    /** Sentinel flag marking a finding as a risk advisory (produced by `ai-risk-scoring`). */
    const val IS_RISK_ADVISORY = "isRiskAdvisory"

    /** Sentinel flag marking a finding as a fix suggestion (produced by `ai-fix-suggestions`). */
    const val IS_FIX_SUGGESTION = "isFixSuggestion"

    /** Sentinel flag marking a finding as label categorization (produced by `ai-change-categorization`). */
    const val IS_CATEGORIZATION = "isCategorization"

    /** Sentinel flag marking a finding as description quality (produced by `ai-description-quality`). */
    const val IS_DESCRIPTION_QUALITY = "isDescriptionQuality"

    /** Sentinel flag marking a finding as a PR split recommendation (produced by `ai-pr-split`). */
    const val IS_PR_SPLIT = "isPrSplit"

    /** Sentinel flag marking a finding as ticket compliance (set by a plugin ticket-compliance analyzer; the built-in one was removed). */
    const val IS_TICKET_COMPLIANCE = "isTicketCompliance"

    /** Sentinel flag marking a finding as a generated description (produced by `ai-generate-description`). */
    const val IS_GENERATED_DESCRIPTION = "isGeneratedDescription"

    /** Risk score (0.0–1.0), produced by `ai-risk-scoring`. */
    const val RISK_SCORE = "riskScore"

    /**
     * Measured process risk composite (0.0–1.0): the one shared risk computation behind `ai.processRisk`,
     * `ai-risk-scoring`, `ai-pr-split`, `ai-change-categorization` and the `ai-mr-summary` risks.
     */
    const val PROCESS_RISK = "processRisk"

    /** Risk level label (e.g., "low", "medium", "high"), produced by `ai-risk-scoring`. */
    const val RISK_LEVEL = "riskLevel"

    /** Semicolon-separated risk factors explaining the risk score, produced by `ai-risk-scoring`. */
    const val RISK_FACTORS = "factors"

    /** AI-suggested MR title, produced by `ai-generate-description`. */
    const val SUGGESTED_TITLE = "suggestedTitle"

    /** AI-suggested MR description body, produced by `ai-generate-description`. */
    const val SUGGESTED_DESCRIPTION = "suggestedDescription"

    /** Quality score (0.0–1.0) for the MR description, produced by `ai-description-quality`. */
    const val QUALITY_SCORE = "qualityScore"

    /** Number of quality issues found, produced by `ai-description-quality`. */
    const val ISSUE_COUNT = "issueCount"

    /** Whether the MR should be split into smaller PRs, produced by `ai-pr-split`. */
    const val SHOULD_SPLIT = "shouldSplit"

    /** Number of suggested splits, produced by `ai-pr-split`. */
    const val SPLIT_COUNT = "splitCount"

    /** JSON-encoded list of related patterns, produced by `ai-similar-issues`. */
    const val RELATED_PATTERNS = "relatedPatterns"

    /** Comma-separated search terms, produced by `ai-similar-issues`. */
    const val SEARCH_TERMS = "searchTerms"

    /** Quality dimension label (e.g., "correctness", "readability"), produced by `ai-code-quality`. */
    const val DIMENSION = "dimension"

    /** Name of the person who introduced a bug, produced by `ai-ownership-analysis`. */
    const val BUG_INTRODUCED_BY = "bugIntroducedBy"

    /** Name of the person who fixed a bug, produced by `ai-ownership-analysis`. */
    const val FIXED_BY = "fixedBy"

    /**
     * Why an AI finding exists (`` `rule` on model; context: ids ``), shown as the inline footer when
     * `report.transparency.inline-why` is on.
     */
    const val WHY = "why"

    /**
     * The MR walkthrough produced by `mr-summary`: a markdown table of review units, then a Mermaid sequence diagram
     * when two or more units interact. Shown when `report.walkthrough` is `collapsed`.
     */
    const val WALKTHROUGH = "walkthrough"

    /** File path associated with a bug-fix correlation, produced by `ai-ownership-analysis`. */
    const val FILE = "file"

    /** Human-readable fix suggestion text, produced by `ai-fix-suggestions`. */
    const val SUGGESTED_FIX = "suggestedFix"

    /** Rule ID of the original finding that triggered a fix suggestion, produced by `ai-fix-suggestions`. */
    const val ORIGINAL_RULE = "originalRule"

    /** Message of the original finding that triggered a fix suggestion, produced by `ai-fix-suggestions`. */
    const val ORIGINAL_MESSAGE = "originalMessage"

    /** Ticket compliance score (0.0–1.0 or "null"), set by a plugin ticket-compliance analyzer. */
    const val COMPLIANCE_SCORE = "complianceScore"

    /** Semicolon-separated fulfilled requirements, set by a plugin ticket-compliance analyzer. */
    const val FULFILLED_REQUIREMENTS = "fulfilledRequirements"

    /** Semicolon-separated missing requirements, set by a plugin ticket-compliance analyzer. */
    const val MISSING_REQUIREMENTS = "missingRequirements"

    /** Semicolon-separated partial requirements, set by a plugin ticket-compliance analyzer. */
    const val PARTIAL_REQUIREMENTS = "partialRequirements"

    /** Comma-separated list of suggested labels, produced by `ai-change-categorization`. */
    const val SUGGESTED_LABELS = "suggestedLabels"

    /** AI reasoning text explaining a categorization decision, produced by `ai-change-categorization`. */
    const val REASONING = "reasoning"

    /**
     * Sentinel flag set by [io.karandash.plugins.ai.core.pipeline.AiMetadataTransformer] after a finding
     * has been fully transformed. Prevents duplicate diff blocks when the transformer is invoked
     * more than once on the same finding.
     */
    const val AI_SUGGESTION_BAKED = "aiSuggestionBaked"

    /**
     * The `test { match ... }` examples a pomsky pattern's author wrote, newline-separated; set by
     * `PatternFix.metadata()` and read by `karandash explain` for the pattern's example.
     */
    const val PATTERN_EXAMPLES = "patternExamples"

    /** Decompiled pattern representation (Pomsky DSL), stored for debugging. */
    const val PATTERN = "pattern"

    /**
     * Anchor fingerprint (rule, path, anchored line text), stamped by the validation engine on findings with a
     * line in the diff. Matches a finding to its earlier inline thread; never rendered.
     */
    const val ANCHOR_FINGERPRINT = "anchorFingerprint"

    /**
     * Set to `"true"` when an AI finding's line could not be verified against the diff and the finding was moved
     * to file level; the summary marks it as unverified.
     */
    const val LINE_DEMOTED = "lineDemoted"

    /**
     * The required format as formatted Pomsky source (e.g. `("feat" | "fix") ": " .+`), shown under a "Format" label
     * by every reporter. Read with `Finding.expectedFormat()`.
     */
    const val EXPECTED_FORMAT = "expectedFormat"

    /**
     * Where the checked value diverges from [EXPECTED_FORMAT]: a JSON object
     * `{"value": "<checked value>", "position": <int>, "expected": "<short token description>", "group": "<named group>"}`
     * (`group` omitted when the divergence is outside a named group). `position` is the index in `value` where matching
     * stopped. Write it with `Mismatch.toMetadataValue()`, read it with `Finding.mismatch()`; reporters render it as a
     * "Where" line.
     */
    const val MISMATCH = "mismatch"

    /**
     * The finding's message, explanation and suggestion in the other output languages: a JSON object
     * `{"ru": {"message": "…", "explanation": "…", "suggestion": "…"}, "pl": {…}, "template": {…, "messageArgs": […]}}`
     * (absent parts left out; `template` holds the English templates a translation pack translates). Written by
     * `Finding.withText`; the host swaps the texts for the report language and removes the key before any format runs.
     */
    const val LOCALIZED_TEXT = "localizedText"

    /** Optional plain-text explanation of [EXPECTED_FORMAT], in the report language; shown in a collapsed block. */
    const val FORMAT_EXPLAIN = "formatExplain"

    /** Optional ASCII railroad diagram of [EXPECTED_FORMAT]; shown in a collapsed block (console: `--verbose` only). */
    const val FORMAT_DIAGRAM = "formatDiagram"

    /** Drift score (0.0-1.0) of the implementation against its design doc; reporters show it as a chip. */
    const val DRIFT_SCORE = "driftScore"

    /** Overall code-quality score of a summary finding; reporters show it as a chip. */
    const val OVERALL_SCORE = "overallScore"

    /** Why a finding stands in for a failed analysis (`parse_error`: the model reply could not be read); shown as a chip. */
    const val ERROR = "error"

    /** `"true"` on a finding served from the incremental cache instead of this run's analysis; the summary row says `(cached)`. */
    const val CACHED = "cached"
}
