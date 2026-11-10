package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.Serializable

/**
 * DORA metrics computed from MR validation data.
 *
 * Approximates lead time, change failure rate, and deployment frequency from
 * [RunRecord] history. Validation runs are the deployment proxy: run frequency stands in for deploy
 * frequency and the share of runs with an error finding for the change failure rate.
 */
object DoraMetricsCalculator {
    private const val HOURS_PER_DAY = 24.0
    private const val SECONDS_PER_HOUR = 3600.0
    private const val PERCENT_MULTIPLIER = 100.0
    private const val DEFAULT_WINDOW_DAYS = 30
    private const val MIN_SPAN_DAYS = 1.0
    private const val MIN_SPAN_SECONDS = 1L
    private const val SINGLE_RUN_FREQUENCY = 1.0

    /**
     * Calculates DORA metrics from the given run records.
     *
     * @param records Historical validation run records.
     * @param windowDays Reporting window in days (informational only, does not filter records).
     * @return Computed [DoraMetrics]; all fields are zero if [records] is empty.
     */
    fun calculate(
        records: List<RunRecord>,
        windowDays: Int = DEFAULT_WINDOW_DAYS
    ): DoraMetrics {
        if (records.isEmpty()) return DoraMetrics()

        val totalRuns = records.size
        val failedRuns = records.count { it.errorCount > 0 }
        val changeFailureRate = failedRuns.toDouble() / totalRuns

        val timestamps = records.map { it.timestamp }
        val deployFrequency = if (timestamps.size >= 2) {
            // RunRecord.timestamp is epoch seconds.
            val spanSeconds = (timestamps.max() - timestamps.min()).coerceAtLeast(MIN_SPAN_SECONDS)
            val spanDays = spanSeconds / (HOURS_PER_DAY * SECONDS_PER_HOUR)
            totalRuns.toDouble() / spanDays.coerceAtLeast(MIN_SPAN_DAYS)
        } else {
            SINGLE_RUN_FREQUENCY
        }

        val avgFindings = records.map { it.totalFindings }.average()

        return DoraMetrics(
            changeFailureRate = changeFailureRate,
            deployFrequencyPerDay = deployFrequency,
            averageFindingsPerRun = avgFindings,
            totalRuns = totalRuns,
            failedRuns = failedRuns,
            windowDays = windowDays,
        )
    }

    /**
     * Formats a [DoraMetrics] result as a human-readable Markdown report.
     *
     * @param metrics The metrics to format.
     * @param language `report.language` of the fixed texts; null is English.
     * @return A Markdown string summarising the metrics.
     */
    fun formatReport(
        metrics: DoraMetrics,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.DORA_TITLE.of(language, metrics.windowDays))
            appendLine(AiReportText.DORA_FAILURE_RATE.of(language, (metrics.changeFailureRate * PERCENT_MULTIPLIER).toInt()))
            appendLine(AiReportText.DORA_DEPLOY_FREQUENCY.of(language, metrics.deployFrequencyPerDay.formatDecimals(1)))
            appendLine(AiReportText.DORA_AVERAGE.of(language, metrics.averageFindingsPerRun.formatDecimals(1)))
            appendLine(AiReportText.DORA_RUNS.of(language, metrics.totalRuns, metrics.failedRuns))
        }
}

/**
 * DORA metrics derived from validation run history.
 *
 * @param changeFailureRate Fraction of runs that had at least one error (0.0–1.0).
 * @param deployFrequencyPerDay Average number of runs per calendar day.
 * @param averageFindingsPerRun Mean total findings across all runs.
 * @param totalRuns Number of runs analysed.
 * @param failedRuns Number of runs with at least one error finding.
 * @param windowDays Reporting window in days (informational).
 */
@Serializable
data class DoraMetrics(
    val changeFailureRate: Double = 0.0,
    val deployFrequencyPerDay: Double = 0.0,
    val averageFindingsPerRun: Double = 0.0,
    val totalRuns: Int = 0,
    val failedRuns: Int = 0,
    val windowDays: Int = 30,
)
