package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.Serializable

/**
 * ROI calculator: estimates time and cost saved by automated validation.
 *
 * Uses per-severity time estimates to approximate how long a developer would
 * have spent finding each issue manually. Example output:
 * "Karandash saved your team 47 hours this month."
 */
object RoiCalculator {
    private const val MINUTES_PER_ERROR_FINDING = 15.0
    private const val MINUTES_PER_WARNING_FINDING = 5.0
    private const val MINUTES_PER_INFO_FINDING = 2.0
    private const val MINUTES_PER_HOUR = 60.0
    private const val DEFAULT_HOURLY_RATE_USD = 75.0

    /**
     * Calculates ROI metrics from the given run records.
     *
     * @param records Historical validation run records to aggregate.
     * @param hourlyRateUsd Developer hourly rate used for cost estimation.
     * @return An [RoiResult] with zero values when [records] is empty.
     */
    fun calculate(
        records: List<RunRecord>,
        hourlyRateUsd: Double = DEFAULT_HOURLY_RATE_USD,
    ): RoiResult {
        val totalErrors = records.sumOf { it.errorCount }
        val totalWarnings = records.sumOf { it.warningCount }
        val totalInfos = records.sumOf { it.infoCount }

        val minutesSaved = (
            totalErrors * MINUTES_PER_ERROR_FINDING +
                totalWarnings * MINUTES_PER_WARNING_FINDING +
                totalInfos * MINUTES_PER_INFO_FINDING
        )

        val hoursSaved = minutesSaved / MINUTES_PER_HOUR
        val costSavedUsd = hoursSaved * hourlyRateUsd

        return RoiResult(
            totalFindings = totalErrors + totalWarnings + totalInfos,
            totalRuns = records.size,
            minutesSaved = minutesSaved,
            hoursSaved = hoursSaved,
            costSavedUsd = costSavedUsd,
            hourlyRateUsd = hourlyRateUsd,
        )
    }

    /**
     * Formats an [RoiResult] as a human-readable Markdown report.
     *
     * @param result The ROI result to format.
     * @param language `report.language` of the fixed texts; null is English.
     * @return A Markdown string summarising time and cost savings.
     */
    fun formatReport(
        result: RoiResult,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.ROI_TITLE.of(language))
            appendLine(AiReportText.ROI_RUNS.of(language, result.totalRuns))
            appendLine(AiReportText.ROI_FINDINGS.of(language, result.totalFindings))
            appendLine(AiReportText.ROI_HOURS.of(language, result.hoursSaved.formatDecimals(1)))
            appendLine(AiReportText.ROI_COST.of(language, result.costSavedUsd.toInt()))
            appendLine(AiReportText.ROI_RATE.of(language, result.hourlyRateUsd.toInt()))
        }
}

/**
 * ROI metrics estimated from validation run history.
 *
 * @param totalFindings Total number of findings caught across all runs.
 * @param totalRuns Number of validation runs analysed.
 * @param minutesSaved Estimated developer minutes saved.
 * @param hoursSaved Estimated developer hours saved.
 * @param costSavedUsd Estimated cost saved in USD.
 * @param hourlyRateUsd Developer hourly rate used for the calculation.
 */
@Serializable
data class RoiResult(
    val totalFindings: Int,
    val totalRuns: Int,
    val minutesSaved: Double,
    val hoursSaved: Double,
    val costSavedUsd: Double,
    val hourlyRateUsd: Double,
)
