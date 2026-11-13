package io.karandash.api.finding

import io.karandash.models.ActivePolicy
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import kotlinx.serialization.Serializable

/**
 * Pre-computed report containing all enriched data that reporters need.
 *
 * Built by [io.karandash.engine.ReportDataBuilder] after validation completes,
 * before reporter dispatch. All reporters receive the same fully-enriched instance,
 * eliminating duplicated aggregation logic across reporter implementations.
 *
 * @property passed Whether validation passed overall (no ERROR-severity findings).
 * @property findings The complete list of validation findings.
 * @property summary Severity-grouped counts and finding lists.
 * @property groupedByLocation Findings grouped by file path and line number.
 * @property inlineEligible Findings with a [Location.FileRef] that has a non-null line - suitable for inline MR comments.
 * @property summaryOnly Findings without an inline-eligible location.
 * @property complianceChecklist Per-rule pass/fail/warn status derived from findings.
 * @property explanations Rule ID to human-readable explanation text.
 * @property metadata Build version and timestamp.
 * @property appliedPolicies Policies that were evaluated during validation.
 * @property skippedRules Rules skipped during validation, with structured reason metadata.
 * @property metrics Performance metrics for the validation run (timing, cache stats).
 * @property ruleSummaries Per-rule aggregated summaries (severity counts, timing, cache).
 * @property enrichmentUsed Map of enrichment provider ID to the set of data keys it supplied.
 * @property baselineComparison Comparison against a previous validation baseline, if available.
 * @property changedFiles File paths that were re-reviewed in this run (from incremental cache).
 *   null = no cache context (resolve all inline comments, post all).
 *   empty = full cache hit (skip inline resolution and posting).
 *   non-empty = partial cache hit (only resolve/post for these files).
 * @property provenance What the run actually reviewed (AI file and chunk coverage); null when nothing reported it.
 */
@Serializable
public data class ValidationReport(
    val passed: Boolean,
    val findings: List<Finding>,
    val summary: FindingSummary,
    val groupedByLocation: Map<LocationKey, List<Finding>>,
    val inlineEligible: List<Finding>,
    val summaryOnly: List<Finding>,
    val complianceChecklist: List<ComplianceItem>,
    val explanations: Map<String, String>,
    val metadata: ReportMetadata,
    val appliedPolicies: List<ActivePolicy> = emptyList(),
    val skippedRules: List<SkippedRuleInfo> = emptyList(),
    val metrics: ValidationMetrics? = null,
    val ruleSummaries: List<RuleSummary> = emptyList(),
    val enrichmentUsed: Map<String, Set<String>> = emptyMap(),
    val baselineComparison: BaselineComparison? = null,
    val changedFiles: Set<String>? = null,
    val provenance: ReviewProvenance? = null,
)

/**
 * Composite key for grouping findings by file location.
 *
 * @property path File path from [Location.FileRef].
 * @property line Line number, or null for file-level findings.
 */
@Serializable
public data class LocationKey(
    val path: String,
    val line: Int?,
)

/**
 * Per-rule compliance status entry for checklist rendering.
 *
 * @property ruleId The rule identifier.
 * @property status Aggregate status across all findings for this rule.
 */
@Serializable
public data class ComplianceItem(
    val ruleId: String,
    val status: ComplianceStatus,
)

/**
 * Aggregate compliance status for a single rule.
 */
@Serializable
public enum class ComplianceStatus {
    /** The rule ran and reported no WARNING or ERROR finding; a rule with only INFO findings is not listed. */
    PASSED,

    /** At least one ERROR-severity finding exists. */
    FAILED,

    /** Highest severity is WARNING (no errors). */
    WARNING,
}

/**
 * Report-level metadata embedded in every [ValidationReport].
 *
 * @property version Karandash version string.
 * @property timestamp Unix epoch seconds when the report was built.
 */
@Serializable
public data class ReportMetadata(
    val version: String,
    val timestamp: Long,
)
