package io.karandash.state

import io.karandash.models.finding.Finding
import kotlinx.serialization.Serializable

/**
 * Return type from rule execution in the validation engine.
 *
 * Pairs the rule's findings with timing data for reporting.
 *
 * @param ruleId The rule that produced this result.
 * @param findings Validation findings (from cache or fresh execution).
 * @param fromCache Whether findings were served from the dependency-aware cache.
 * @param executionDurationNs How long the rule took to execute (wall-clock).
 * @param startOffsetNs Monotonic offset from engine start when rule execution began.
 * @param endOffsetNs Monotonic offset from engine start when rule execution ended.
 */
@Serializable
data class RuleExecutionResult(
    val ruleId: String,
    val findings: List<Finding>,
    val fromCache: Boolean,
    val executionDurationNs: Long,
    val startOffsetNs: Long = 0,
    val endOffsetNs: Long = 0,
)
