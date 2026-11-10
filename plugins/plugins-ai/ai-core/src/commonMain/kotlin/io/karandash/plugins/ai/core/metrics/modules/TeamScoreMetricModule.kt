package io.karandash.plugins.ai.core.metrics.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.plugins.ai.core.reporters.HistoryAwareMetricModule
import io.karandash.plugins.ai.core.reporters.TeamScorecardGenerator
import io.karandash.plugins.ai.statistics.RunRecord

/**
 * Metric module: composite team hygiene score (0-100).
 *
 * Requires historical run records (the bounded recent-run window).
 */
class TeamScoreMetricModule : HistoryAwareMetricModule {
    override val id: String get() = ID
    override val name: String get() = "Team Score"
    override val version: String get() = VERSION
    override val metricId: String get() = METRIC_ID
    override val metricName: String get() = "Team Score"
    override val unit: String get() = "score"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun compute(
        context: ReportContext,
        history: List<RunRecord>,
    ): MetricResult =
        MetricResult(metricId = METRIC_ID, value = TeamScorecardGenerator.generate(history).compositeScore.toDouble(), unit = unit)

    companion object {
        const val ID = "ai-metric-team-score"
        const val METRIC_ID = "team-score"
        private const val VERSION = "1.0.0"
    }
}
