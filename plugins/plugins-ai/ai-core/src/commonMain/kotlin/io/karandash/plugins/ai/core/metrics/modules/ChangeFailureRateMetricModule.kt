package io.karandash.plugins.ai.core.metrics.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.plugins.ai.core.reporters.DoraMetricsCalculator
import io.karandash.plugins.ai.core.reporters.HistoryAwareMetricModule
import io.karandash.plugins.ai.statistics.RunRecord

/**
 * Metric module: fraction of validation runs that had at least one error finding.
 *
 * Requires historical run records (the bounded recent-run window).
 * The recorded validation runs are the deployment proxy: a run with an error finding counts as a failed change.
 */
class ChangeFailureRateMetricModule : HistoryAwareMetricModule {
    override val id: String get() = ID
    override val name: String get() = "Change Failure Rate"
    override val version: String get() = VERSION
    override val metricId: String get() = METRIC_ID
    override val metricName: String get() = "Change Failure Rate"
    override val unit: String get() = "percent"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun compute(
        context: ReportContext,
        history: List<RunRecord>,
    ): MetricResult = MetricResult(metricId = METRIC_ID, value = DoraMetricsCalculator.calculate(history).changeFailureRate, unit = unit)

    companion object {
        const val ID = "ai-metric-change-failure-rate"
        const val METRIC_ID = "change-failure-rate"
        private const val VERSION = "1.0.0"
    }
}
