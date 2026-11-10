package io.karandash.api.finding

import kotlinx.serialization.Serializable

/**
 * Describes a rule that was skipped during validation, including the reason.
 *
 * @property ruleId The identifier of the skipped rule.
 * @property reason Human-readable description of why the rule was skipped.
 * @property category Structured category for programmatic filtering.
 * @property policy Label of the policy behind a [SkipCategory.POLICY_OVERRIDE_NOT_APPLIED] skip; null otherwise.
 */
@Serializable
public data class SkippedRuleInfo(
    val ruleId: String,
    val reason: String = "",
    val category: SkipCategory = SkipCategory.OTHER,
    val policy: String? = null,
)

/**
 * Category of skip reason, enabling structured filtering and display grouping.
 */
@Serializable
public enum class SkipCategory {
    /** Rule's `when:` condition evaluated to false. */
    WHEN_CONDITION,

    /** A prior rule with `bailOnFail` produced an ERROR finding. */
    BAIL_ON_FAIL,

    /** Fallback rule's concern area was already covered by a pass-1 rule. */
    CONCERN_AREA_COVERED,

    /** Rule disabled by a policy override. */
    DISABLED_BY_POLICY,

    /** AI token or cost budget exhausted. */
    AI_BUDGET_EXHAUSTED,

    /** AI provider returned a rate-limit error. */
    AI_RATE_LIMITED,

    /** AI rule gated by file patterns, size, labels, or branches. */
    AI_GATED,

    /** AI provider error (auth, model unavailable, timeout, etc.). */
    AI_PROVIDER_ERROR,

    /** AI dry-run mode - no API calls made. */
    AI_DRY_RUN,

    /** AI size policy skip (MR too large for analysis). */
    AI_SIZE_POLICY,

    /** Policy override not applied - policy's when condition evaluated to false. */
    POLICY_OVERRIDE_NOT_APPLIED,

    /** No handler registered for this rule ID. */
    NO_HANDLER,

    /** Rule declares [io.karandash.api.module.RuleModule.requiredEnrichment] IDs that are unavailable. */
    MISSING_REQUIRED_ENRICHMENT,

    /** Uncategorized skip reason. */
    OTHER,

    /**
     * Rule requires a merge-request field the platform never fills
     * ([io.karandash.api.module.PlatformServiceModule.unavailableMergeRequestFields]).
     */
    MISSING_MERGE_REQUEST_FIELD,

    /** AI review skipped: every changed file is whitespace-only, a lockfile, generated or docs. */
    AI_TRIVIAL_DIFF,

    /** Rule's `when:` expression failed to evaluate (parse or predicate error); the rule did not run. */
    WHEN_ERROR,
}
