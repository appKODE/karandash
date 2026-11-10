package io.karandash.api.context

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable

/**
 * Cross-rule context passed to [io.karandash.api.module.FindingsAwareRuleModule] implementations.
 *
 * Contains findings from priority groups that have already completed (pass 1),
 * enabling pass-2 rules to make informed decisions (fallback, supplement, skip).
 *
 * This class is immutable and safe to share across coroutines.
 *
 * @property priorFindings Findings from all completed priority groups in pass 1.
 */
@Serializable
public data class ValidationContext(
    val priorFindings: List<Finding> = emptyList(),
) {
    /** Rule IDs that produced at least one finding. */
    val activeRules: Set<String> by lazy {
        priorFindings.map { it.rule }.toSet()
    }

    /** Returns `true` if [ruleId] produced any findings. */
    public fun hasFindings(ruleId: String): Boolean = activeRules.contains(ruleId)

    /** Returns all findings from [ruleId]. */
    public fun findingsFor(ruleId: String): List<Finding> = priorFindings.filter { it.rule == ruleId }

    /** Returns findings from [ruleId] at or above [minSeverity]. */
    public fun findingsFor(
        ruleId: String,
        minSeverity: Severity,
    ): List<Finding> = priorFindings.filter { it.rule == ruleId && it.severity <= minSeverity }
}
