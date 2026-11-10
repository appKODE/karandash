package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Policy drift detector: compares current validation results against a historical baseline.
 *
 * Alerts on:
 * - Regressions: rules that fire more often than baseline
 * - Improvements: rules that fire less often
 * - New rules: rules that didn't exist in baseline
 * - Missing rules: rules that existed in baseline but no longer fire
 */
object PolicyDriftDetector {
    private const val DRIFT_THRESHOLD_PERCENT = 10
    private const val PERCENT = 100.0

    /**
     * Compares [recent] run records against [baseline] run records.
     *
     * Returns an empty report when either list is empty.
     *
     * @param recent Recent run records (current sprint/period).
     * @param baseline Baseline run records (previous sprint/period).
     * @return Drift analysis with per-rule changes sorted by absolute delta descending.
     */
    fun detect(
        recent: List<RunRecord>,
        baseline: List<RunRecord>,
    ): PolicyDriftReport {
        if (recent.isEmpty() || baseline.isEmpty()) return PolicyDriftReport()

        val recentRates = computeFireRates(recent)
        val baselineRates = computeFireRates(baseline)

        val allRules = recentRates.keys + baselineRates.keys
        val drifts = mutableListOf<RuleDrift>()

        for (rule in allRules) {
            val recentRate = recentRates[rule] ?: 0.0
            val baselineRate = baselineRates[rule] ?: 0.0
            val deltaPercent = (recentRate - baselineRate) * PERCENT

            when {
                rule !in baselineRates -> drifts.add(
                    RuleDrift(
                        ruleId = rule,
                        type = RuleDriftType.NEW_RULE,
                        baselineRate = 0.0,
                        currentRate = recentRate,
                        deltaPercent = deltaPercent,
                    ),
                )

                rule !in recentRates -> drifts.add(
                    RuleDrift(
                        ruleId = rule,
                        type = RuleDriftType.MISSING_RULE,
                        baselineRate = baselineRate,
                        currentRate = 0.0,
                        deltaPercent = -baselineRate * PERCENT,
                    ),
                )

                deltaPercent > DRIFT_THRESHOLD_PERCENT -> drifts.add(
                    RuleDrift(
                        ruleId = rule,
                        type = RuleDriftType.REGRESSION,
                        baselineRate = baselineRate,
                        currentRate = recentRate,
                        deltaPercent = deltaPercent,
                    ),
                )

                deltaPercent < -DRIFT_THRESHOLD_PERCENT -> drifts.add(
                    RuleDrift(
                        ruleId = rule,
                        type = RuleDriftType.IMPROVEMENT,
                        baselineRate = baselineRate,
                        currentRate = recentRate,
                        deltaPercent = deltaPercent,
                    ),
                )
            }
        }

        return PolicyDriftReport(
            drifts = drifts.sortedByDescending { kotlin.math.abs(it.deltaPercent) },
            recentRuns = recent.size,
            baselineRuns = baseline.size,
            hasRegression = drifts.any { it.type == RuleDriftType.REGRESSION },
        )
    }

    /**
     * Formats a [PolicyDriftReport] as a human-readable Markdown string.
     *
     * @param report The report to render.
     * @param language The `report.language` of the fixed texts; null renders English.
     * @return Markdown string with per-rule drift entries and a regression warning if applicable.
     */
    fun formatReport(
        report: PolicyDriftReport,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.DRIFT_TITLE.of(language))
            appendLine(AiReportText.DRIFT_COUNTS.of(language, report.recentRuns, report.baselineRuns))
            if (report.drifts.isEmpty()) {
                appendLine(AiReportText.DRIFT_NONE.of(language))
            } else {
                appendLine()
                for (drift in report.drifts) {
                    val arrow = if (drift.deltaPercent > 0) "↑" else "↓"
                    appendLine(
                        "- [${AiReportText.label(drift.type.name, language)}] ${drift.ruleId}: $arrow${drift.deltaPercent.roundToInt()}% " +
                            "(${(drift.baselineRate * PERCENT).roundToInt()}% → ${(drift.currentRate * PERCENT).roundToInt()}%)",
                    )
                }
            }
            if (report.hasRegression) {
                appendLine()
                appendLine(AiReportText.DRIFT_WARNING.of(language))
            }
        }

    private fun computeFireRates(records: List<RunRecord>): Map<String, Double> {
        val allRules = records.flatMap { it.findingsByRule.keys }.toSet()
        return allRules.associateWith { rule ->
            records.count { (it.findingsByRule[rule] ?: 0) > 0 }.toDouble() / records.size
        }
    }
}

/**
 * Result of a policy drift analysis comparing recent runs to a baseline.
 *
 * @param drifts List of detected per-rule drifts, sorted by absolute delta descending.
 * @param recentRuns Number of recent run records analyzed.
 * @param baselineRuns Number of baseline run records analyzed.
 * @param hasRegression True when at least one [RuleDriftType.REGRESSION] was detected.
 */
@Serializable
data class PolicyDriftReport(
    val drifts: List<RuleDrift> = emptyList(),
    val recentRuns: Int = 0,
    val baselineRuns: Int = 0,
    val hasRegression: Boolean = false,
)

/**
 * Describes a detected drift for a single rule between baseline and recent periods.
 *
 * @param ruleId Rule identifier.
 * @param type Category of drift detected.
 * @param baselineRate Fraction of baseline runs that fired this rule.
 * @param currentRate Fraction of recent runs that fired this rule.
 * @param deltaPercent Change in fire rate as a percentage point value.
 */
@Serializable
data class RuleDrift(
    val ruleId: String,
    val type: RuleDriftType,
    val baselineRate: Double,
    val currentRate: Double,
    val deltaPercent: Double,
)

/** Categories of rule drift detected by [PolicyDriftDetector]. */
enum class RuleDriftType { REGRESSION, IMPROVEMENT, NEW_RULE, MISSING_RULE }
