package io.karandash.plugins.ai.core.metrics.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.plugins.ai.core.reporters.HistoryAwareMetricModule
import io.karandash.plugins.ai.core.reporters.RoiCalculator
import io.karandash.plugins.ai.statistics.RunRecord

/**
 * Metric module: estimated developer hours saved by automated validation.
 *
 * Requires historical run records (the bounded recent-run window).
 */
class HoursSavedMetricModule : HistoryAwareMetricModule {
    override val id: String get() = ID
    override val name: String get() = "Hours Saved"
    override val version: String get() = VERSION
    override val metricId: String get() = METRIC_ID
    override val metricName: String get() = "Hours Saved"
    override val unit: String get() = "hours"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun compute(
        context: ReportContext,
        history: List<RunRecord>,
    ): MetricResult = MetricResult(metricId = METRIC_ID, value = RoiCalculator.calculate(history).hoursSaved, unit = unit)

    companion object {
        const val ID = "ai-metric-hours-saved"
        const val METRIC_ID = "hours-saved"
        private const val VERSION = "1.0.0"
    }
}
