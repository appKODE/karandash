package io.karandash.plugins.ai.core.metrics.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.plugins.ai.core.metrics.CognitiveLoadEstimator

/**
 * Metric module wrapper for [CognitiveLoadEstimator].
 *
 * Estimates cognitive load for code review based on LOC thresholds.
 * Delegates to the existing estimator using the MR from the report context.
 */
class CognitiveLoadMetricModule : AiMetricModule {
    override val id: String get() = ID
    override val name: String get() = "Cognitive Load"
    override val version: String get() = VERSION
    override val metricId: String get() = METRIC_ID
    override val metricName: String get() = "Cognitive Load"
    override val unit: String get() = "minutes"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.MERGE_REQUEST)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun compute(context: ReportContext): MetricResult {
        val metrics = CognitiveLoadEstimator.estimate(context.mr)
        return MetricResult(
            metricId = METRIC_ID,
            value = metrics.estimatedReviewMinutes,
            unit = unit,
            label = metrics.riskLevel.name,
        )
    }

    companion object {
        const val ID = "ai-metric-cognitive-load"
        const val METRIC_ID = "cognitive-load"
        private const val VERSION = "1.0.0"
    }
}
