package io.karandash.api.finding

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable

/**
 * Aggregated summary of a list of [Finding]s, grouped by [Severity].
 *
 * Eliminates the repeated `findings.count { it.severity == ... }` pattern
 * found across multiple reporter modules.
 *
 * @property errors Number of [Severity.ERROR] findings.
 * @property warnings Number of [Severity.WARNING] findings.
 * @property info Number of [Severity.INFO] findings.
 * @property errorFindings The error-severity findings.
 * @property warningFindings The warning-severity findings.
 * @property infoFindings The info-severity findings.
 */
@Serializable
public data class FindingSummary(
    val errors: Int,
    val warnings: Int,
    val info: Int,
    val errorFindings: List<Finding>,
    val warningFindings: List<Finding>,
    val infoFindings: List<Finding>,
) {
    /** Total number of findings across all severities. */
    val total: Int get() = errors + warnings + info
}

/**
 * Summarizes this list of [Finding]s into a [FindingSummary] grouped by severity.
 */
public fun List<Finding>.summarize(): FindingSummary {
    val errorList = filter { it.severity == Severity.ERROR }
    val warnList = filter { it.severity == Severity.WARNING }
    val infoList = filter { it.severity == Severity.INFO }
    return FindingSummary(errorList.size, warnList.size, infoList.size, errorList, warnList, infoList)
}
