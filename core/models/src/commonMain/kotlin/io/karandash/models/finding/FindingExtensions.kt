package io.karandash.models.finding

/** Groups findings by [Severity], returning a map keyed by severity level. */
fun List<Finding>.bySeverity(): Map<Severity, List<Finding>> = groupBy { it.severity }

/** Groups findings by rule ID, returning a map keyed by rule name. */
fun List<Finding>.groupedByRule(): Map<String, List<Finding>> = groupBy { it.rule }

/** Returns the count of findings with [Severity.ERROR]. */
fun List<Finding>.errorsCount(): Int = count { it.severity == Severity.ERROR }

/** Returns `true` if at least one finding has [Severity.ERROR]. */
fun List<Finding>.hasErrors(): Boolean = any { it.severity == Severity.ERROR }

/**
 * Deduplicates findings by fingerprint, keeping the highest-severity instance.
 * Findings without fingerprints are always kept.
 */
fun List<Finding>.deduplicated(): List<Finding> {
    val withFingerprint = filter { it.fingerprint != null }
    val withoutFingerprint = filter { it.fingerprint == null }
    val deduped = withFingerprint
        .groupBy { it.fingerprint }
        .values
        .map { group -> group.minByOrNull { it.severity.ordinal } ?: group.first() }
    return deduped + withoutFingerprint
}

/** Groups findings by fingerprint for inspection or reporting. */
fun List<Finding>.groupedByFingerprint(): Map<String?, List<Finding>> = groupBy { it.fingerprint }

/** Sorts findings by priority (lower number = higher priority), then severity, then rule. */
fun List<Finding>.sortedByPriority(): List<Finding> =
    sortedWith(
        compareBy({ it.priority }, { it.severity.ordinal }, { it.rule }),
    )

/** Filters to findings in a specific [FindingCategory]. */
fun List<Finding>.inCategory(category: FindingCategory): List<Finding> = filter { it.category == category }
