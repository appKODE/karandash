package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.HistoryAwareReportModule
import io.karandash.plugins.ai.core.reporters.RoiCalculator
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [RoiCalculator].
 *
 * Estimates time and cost saved by automated validation. Requires historical data.
 */
class RoiReportModule : HistoryAwareReportModule {
    override val id: String get() = ID
    override val name: String get() = "ROI Report"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "ROI Report"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(
        context: ReportContext,
        history: List<RunRecord>,
    ): ReportResult {
        val result = RoiCalculator.calculate(history)
        val formatted = RoiCalculator.formatReport(result, LanguagePromptOverlays.responseLanguageOf(context.config))
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf(
                    "hoursSaved" to JsonPrimitive(result.hoursSaved),
                    "costSavedUsd" to JsonPrimitive(result.costSavedUsd),
                    "totalFindings" to JsonPrimitive(result.totalFindings),
                ),
            ),
            formatted = formatted,
            metrics = mapOf(
                "hours-saved" to result.hoursSaved,
                "cost-saved" to result.costSavedUsd,
            ),
        )
    }

    companion object {
        const val ID = "ai-report-roi"
        const val REPORT_ID = "roi"
        private const val VERSION = "1.0.0"
    }
}
