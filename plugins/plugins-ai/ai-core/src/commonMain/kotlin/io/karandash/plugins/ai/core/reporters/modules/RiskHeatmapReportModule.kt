package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.RiskHeatmapGenerator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [RiskHeatmapGenerator].
 *
 * Computes finding density per file and directory, producing a risk heatmap.
 */
class RiskHeatmapReportModule : AiReportModule {
    override val id: String get() = ID
    override val name: String get() = "Risk Heatmap"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "Risk Heatmap"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.FINDINGS)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(context: ReportContext): ReportResult {
        val heatmap = RiskHeatmapGenerator.generate(context.findings)
        val formatted = RiskHeatmapGenerator.formatReport(heatmap, LanguagePromptOverlays.responseLanguageOf(context.config))
        val topScore = heatmap.files.firstOrNull()?.score ?: 0
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf(
                    "fileCount" to JsonPrimitive(heatmap.files.size),
                    "directoryCount" to JsonPrimitive(heatmap.directories.size),
                    "topFileScore" to JsonPrimitive(topScore),
                ),
            ),
            formatted = formatted,
            metrics = mapOf("hotspot-density" to topScore.toDouble()),
        )
    }

    companion object {
        const val ID = "ai-report-risk-heatmap"
        const val REPORT_ID = "risk-heatmap"
        private const val VERSION = "1.0.0"
    }
}
