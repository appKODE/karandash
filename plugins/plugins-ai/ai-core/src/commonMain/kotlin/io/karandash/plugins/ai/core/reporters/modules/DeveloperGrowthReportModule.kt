package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.DeveloperGrowthReportGenerator
import io.karandash.plugins.ai.core.reporters.HistoryAwareReportModule
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [DeveloperGrowthReportGenerator].
 *
 * Tracks the MR author's quality trajectory over time from the runs recorded for that author.
 * Returns a placeholder when history is unavailable.
 */
class DeveloperGrowthReportModule : HistoryAwareReportModule {
    override val id: String get() = ID
    override val name: String get() = "Developer Growth"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "Developer Growth"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.MERGE_REQUEST, ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(
        context: ReportContext,
        history: List<RunRecord>,
    ): ReportResult {
        val authorId = context.mr.author.username
        val data = DeveloperGrowthReportGenerator.generate(authorId, history.filter { it.author == authorId })
        val formatted = DeveloperGrowthReportGenerator.formatReport(data, LanguagePromptOverlays.responseLanguageOf(context.config))
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf(
                    "authorId" to JsonPrimitive(data.authorId),
                    "trend" to JsonPrimitive(data.trend.name),
                    "improvementPercent" to JsonPrimitive(data.improvementPercent),
                ),
            ),
            formatted = formatted,
        )
    }

    companion object {
        const val ID = "ai-report-developer-growth"
        const val REPORT_ID = "developer-growth"
        private const val VERSION = "1.0.0"
    }
}
