package io.karandash.api.finding

import kotlinx.serialization.Serializable

/**
 * Per-rule aggregated summary derived from validation findings.
 *
 * Provides a quick overview of each rule's outcome without needing to
 * iterate the full findings list. Reporters use this for summary tables.
 *
 * @property ruleId The rule identifier.
 * @property errors Number of [io.karandash.models.finding.Severity.ERROR] findings for this rule.
 * @property warnings Number of [io.karandash.models.finding.Severity.WARNING] findings for this rule.
 * @property infos Number of [io.karandash.models.finding.Severity.INFO] findings for this rule.
 * @property hasFixSuggestions Whether any finding from this rule carries fix suggestions.
 * @property executionTimeMs Rule execution time in milliseconds (0 when timing data is unavailable).
 * @property fromCache Whether this rule's findings were served from cache.
 */
@Serializable
public data class RuleSummary(
    val ruleId: String,
    val errors: Int = 0,
    val warnings: Int = 0,
    val infos: Int = 0,
    val hasFixSuggestions: Boolean = false,
    val executionTimeMs: Long = 0,
    val fromCache: Boolean = false,
)
