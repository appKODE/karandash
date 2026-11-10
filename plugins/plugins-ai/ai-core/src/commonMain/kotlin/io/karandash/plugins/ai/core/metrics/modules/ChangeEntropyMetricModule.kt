package io.karandash.plugins.ai.core.metrics.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.plugins.ai.core.metrics.ChangeEntropyCalculator

/**
 * Metric module wrapper for [ChangeEntropyCalculator].
 *
 * Computes Shannon entropy of the code change distribution.
 * High entropy indicates "shotgun surgery" -- scattered changes correlated
 * with higher defect rates.
 */
class ChangeEntropyMetricModule : AiMetricModule {
    override val id: String get() = ID
    override val name: String get() = "Change Entropy"
    override val version: String get() = VERSION
    override val metricId: String get() = METRIC_ID
    override val metricName: String get() = "Change Entropy"
    override val unit: String get() = "bits"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.MERGE_REQUEST)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun compute(context: ReportContext): MetricResult {
        val metrics = ChangeEntropyCalculator.calculate(context.mr)
        return MetricResult(
            metricId = METRIC_ID,
            value = metrics.normalizedEntropy,
            unit = unit,
            label = "H=${metrics.fileEntropy.formatDecimals(2)}",
        )
    }

    companion object {
        const val ID = "ai-metric-change-entropy"
        const val METRIC_ID = "change-entropy"
        private const val VERSION = "1.0.0"
    }
}
