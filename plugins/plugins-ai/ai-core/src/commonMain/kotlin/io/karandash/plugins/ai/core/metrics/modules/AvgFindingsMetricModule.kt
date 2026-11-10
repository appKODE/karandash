package io.karandash.plugins.ai.core.metrics.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput

/**
 * Metric module: average number of findings per validation run.
 *
 * Uses current run findings count as a single-sample proxy.
 */
class AvgFindingsMetricModule : AiMetricModule {
    override val id: String get() = ID
    override val name: String get() = "Average Findings"
    override val version: String get() = VERSION
    override val metricId: String get() = METRIC_ID
    override val metricName: String get() = "Average Findings"
    override val unit: String get() = "count"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.FINDINGS)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun compute(context: ReportContext): MetricResult =
        MetricResult(
            metricId = METRIC_ID,
            value = context.findings.size.toDouble(),
            unit = unit,
            label = "${context.findings.size} findings",
        )

    companion object {
        const val ID = "ai-metric-avg-findings"
        const val METRIC_ID = "avg-findings"
        private const val VERSION = "1.0.0"
    }
}
