package io.karandash.plugins.ai.core.metrics.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.plugins.ai.core.reporters.DoraMetricsCalculator
import io.karandash.plugins.ai.core.reporters.HistoryAwareMetricModule
import io.karandash.plugins.ai.statistics.RunRecord

/**
 * Metric module: average number of validation runs per calendar day.
 *
 * Requires historical run records (the bounded recent-run window).
 * The recorded validation runs are the deployment proxy: each run counts as one deployment.
 */
class DeployFrequencyMetricModule : HistoryAwareMetricModule {
    override val id: String get() = ID
    override val name: String get() = "Deploy Frequency"
    override val version: String get() = VERSION
    override val metricId: String get() = METRIC_ID
    override val metricName: String get() = "Deploy Frequency"
    override val unit: String get() = "per-day"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun compute(
        context: ReportContext,
        history: List<RunRecord>,
    ): MetricResult =
        MetricResult(metricId = METRIC_ID, value = DoraMetricsCalculator.calculate(history).deployFrequencyPerDay, unit = unit)

    companion object {
        const val ID = "ai-metric-deploy-frequency"
        const val METRIC_ID = "deploy-frequency"
        private const val VERSION = "1.0.0"
    }
}
