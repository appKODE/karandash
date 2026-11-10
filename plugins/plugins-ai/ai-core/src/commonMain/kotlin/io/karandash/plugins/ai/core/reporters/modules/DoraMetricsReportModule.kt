package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.DoraMetricsCalculator
import io.karandash.plugins.ai.core.reporters.HistoryAwareReportModule
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [DoraMetricsCalculator].
 *
 * Approximates DORA metrics (lead time, change failure rate, deploy frequency)
 * from validation run history. Requires historical data. The recorded validation runs are the deployment
 * proxy: run frequency stands in for deploy frequency and runs with an error finding for failed changes.
 */
class DoraMetricsReportModule : HistoryAwareReportModule {
    override val id: String get() = ID
    override val name: String get() = "DORA Metrics"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "DORA Metrics"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(
        context: ReportContext,
        history: List<RunRecord>,
    ): ReportResult {
        val metrics = DoraMetricsCalculator.calculate(history)
        val formatted = DoraMetricsCalculator.formatReport(metrics, LanguagePromptOverlays.responseLanguageOf(context.config))
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf(
                    "changeFailureRate" to JsonPrimitive(metrics.changeFailureRate),
                    "deployFrequencyPerDay" to JsonPrimitive(metrics.deployFrequencyPerDay),
                    "averageFindingsPerRun" to JsonPrimitive(metrics.averageFindingsPerRun),
                ),
            ),
            formatted = formatted,
            metrics = mapOf(
                "change-failure-rate" to metrics.changeFailureRate,
                "deploy-frequency" to metrics.deployFrequencyPerDay,
            ),
        )
    }

    companion object {
        const val ID = "ai-report-dora-metrics"
        const val REPORT_ID = "dora-metrics"
        private const val VERSION = "1.0.0"
    }
}
