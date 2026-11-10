package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.HistoryAwareReportModule
import io.karandash.plugins.ai.core.reporters.PolicyDriftDetector
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [PolicyDriftDetector].
 *
 * Compares current validation results against a historical baseline to detect
 * regressions and improvements. Requires historical run records.
 */
class PolicyDriftReportModule : HistoryAwareReportModule {
    override val id: String get() = ID
    override val name: String get() = "Policy Drift Analysis"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "Policy Drift Analysis"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(
        context: ReportContext,
        history: List<RunRecord>,
    ): ReportResult {
        val language = LanguagePromptOverlays.responseLanguageOf(context.config)
        val report = PolicyDriftDetector.detect(
            recent = history.drop(history.size / 2),
            baseline = history.take(history.size / 2),
        )
        val formatted = PolicyDriftDetector.formatReport(report, language)
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf(
                    "driftCount" to JsonPrimitive(report.drifts.size),
                    "hasRegression" to JsonPrimitive(report.hasRegression),
                ),
            ),
            formatted = formatted,
        )
    }

    companion object {
        const val ID = "ai-report-policy-drift"
        const val REPORT_ID = "policy-drift"
        private const val VERSION = "1.0.0"
    }
}
