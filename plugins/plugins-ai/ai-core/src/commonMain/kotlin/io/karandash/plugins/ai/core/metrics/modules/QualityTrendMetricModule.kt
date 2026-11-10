package io.karandash.plugins.ai.core.metrics.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.plugins.ai.core.reporters.DeveloperGrowthReportGenerator
import io.karandash.plugins.ai.core.reporters.HistoryAwareMetricModule
import io.karandash.plugins.ai.statistics.RunRecord

/**
 * Metric module: quality trend percentage (positive = improving, negative = declining).
 *
 * Requires historical run records (the bounded recent-run window).
 */
class QualityTrendMetricModule : HistoryAwareMetricModule {
    override val id: String get() = ID
    override val name: String get() = "Quality Trend"
    override val version: String get() = VERSION
    override val metricId: String get() = METRIC_ID
    override val metricName: String get() = "Quality Trend"
    override val unit: String get() = "percent"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun compute(
        context: ReportContext,
        history: List<RunRecord>,
    ): MetricResult =
        MetricResult(
            metricId = METRIC_ID,
            value = DeveloperGrowthReportGenerator.generate(TEAM_SCOPE, history).improvementPercent,
            unit = unit,
        )

    companion object {
        const val ID = "ai-metric-quality-trend"
        private const val TEAM_SCOPE = "team"
        const val METRIC_ID = "quality-trend"
        private const val VERSION = "1.0.0"
    }
}
