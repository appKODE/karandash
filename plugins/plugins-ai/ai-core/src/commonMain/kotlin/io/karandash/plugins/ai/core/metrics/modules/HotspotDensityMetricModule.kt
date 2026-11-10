package io.karandash.plugins.ai.core.metrics.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.plugins.ai.core.metrics.HotspotAnalyzer
import io.karandash.plugins.ai.core.metrics.HotspotLevel

/**
 * Metric module wrapper for [HotspotAnalyzer].
 *
 * Reports the highest hotspot score across all files in the MR.
 * High values indicate files that are both frequently changed and complex.
 */
class HotspotDensityMetricModule : AiMetricModule {
    override val id: String get() = ID
    override val name: String get() = "Hotspot Density"
    override val version: String get() = VERSION
    override val metricId: String get() = METRIC_ID
    override val metricName: String get() = "Hotspot Density"
    override val unit: String get() = "score"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.MERGE_REQUEST)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun compute(context: ReportContext): MetricResult {
        val hotspots = HotspotAnalyzer.analyze(context.mr)
        val topEntry = hotspots.firstOrNull()
        val topScore = topEntry?.hotspotScore ?: 0.0
        val highCount = hotspots.count { it.level == HotspotLevel.HIGH }
        return MetricResult(
            metricId = METRIC_ID,
            value = topScore,
            unit = unit,
            label = "$highCount high-risk file(s)",
        )
    }

    companion object {
        const val ID = "ai-metric-hotspot-density"
        const val METRIC_ID = "hotspot-density"
        private const val VERSION = "1.0.0"
    }
}
