package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.CrossPlatformConsistencyChecker
import io.karandash.plugins.ai.core.reporters.HistoryAwareReportModule
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [CrossPlatformConsistencyChecker].
 *
 * Compares validation results across platforms (GitLab, GitHub, etc.) to detect
 * policy drift. Requires historical data.
 */
class CrossPlatformConsistencyReportModule : HistoryAwareReportModule {
    override val id: String get() = ID
    override val name: String get() = "Cross-Platform Consistency"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "Cross-Platform Consistency"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(
        context: ReportContext,
        history: List<RunRecord>,
    ): ReportResult {
        val language = LanguagePromptOverlays.responseLanguageOf(context.config)
        val report = CrossPlatformConsistencyChecker.compare(history.groupBy { it.platform ?: UNKNOWN_PLATFORM }, language)
        val formatted = CrossPlatformConsistencyChecker.formatReport(report, language)
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf(
                    "platformCount" to JsonPrimitive(report.platforms.size),
                    "isConsistent" to JsonPrimitive(report.isConsistent),
                ),
            ),
            formatted = formatted,
        )
    }

    companion object {
        const val ID = "ai-report-cross-platform"
        private const val UNKNOWN_PLATFORM = "unknown"
        const val REPORT_ID = "cross-platform-consistency"
        private const val VERSION = "1.0.0"
    }
}
