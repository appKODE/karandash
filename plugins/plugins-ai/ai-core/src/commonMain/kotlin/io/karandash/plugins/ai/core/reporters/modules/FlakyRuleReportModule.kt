package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.FlakyRuleDetector
import io.karandash.plugins.ai.core.reporters.HistoryAwareReportModule
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [FlakyRuleDetector].
 *
 * Identifies rules with inconsistent behavior (flip-floppers, never-fire, always-fire).
 * Requires historical run records -- returns empty report when history is unavailable.
 */
class FlakyRuleReportModule : HistoryAwareReportModule {
    override val id: String get() = ID
    override val name: String get() = "Flaky Rule Analysis"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "Flaky Rule Analysis"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(
        context: ReportContext,
        history: List<RunRecord>,
    ): ReportResult {
        val language = LanguagePromptOverlays.responseLanguageOf(context.config)
        val report = FlakyRuleDetector.detect(history, language)
        val formatted = FlakyRuleDetector.formatReport(report, language)
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf(
                    "issueCount" to JsonPrimitive(report.issues.size),
                    "rulesAnalyzed" to JsonPrimitive(report.rulesAnalyzed),
                    "runsAnalyzed" to JsonPrimitive(report.runsAnalyzed),
                ),
            ),
            formatted = formatted,
        )
    }

    companion object {
        const val ID = "ai-report-flaky-rules"
        const val REPORT_ID = "flaky-rules"
        private const val VERSION = "1.0.0"
    }
}
