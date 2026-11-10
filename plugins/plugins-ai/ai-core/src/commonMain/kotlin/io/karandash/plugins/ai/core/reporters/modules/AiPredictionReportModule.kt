package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.AiPredictionReporter
import io.karandash.plugins.ai.core.reporters.HistoryAwareReportModule
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [AiPredictionReporter].
 *
 * Predicts which rules will fail before running them, using historical fire rates
 * combined with MR characteristics. Requires both MR and history data.
 */
class AiPredictionReportModule : HistoryAwareReportModule {
    override val id: String get() = ID
    override val name: String get() = "Rule Failure Predictions"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "Rule Failure Predictions"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.MERGE_REQUEST, ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(
        context: ReportContext,
        history: List<RunRecord>,
    ): ReportResult {
        val language = LanguagePromptOverlays.responseLanguageOf(context.config)
        val predictions = AiPredictionReporter.predict(context.mr, history)
        val formatted = AiPredictionReporter.formatReport(predictions, language)
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf("predictionCount" to JsonPrimitive(predictions.size)),
            ),
            formatted = formatted,
        )
    }

    companion object {
        const val ID = "ai-report-predictions"
        const val REPORT_ID = "predictions"
        private const val VERSION = "1.0.0"
    }
}
