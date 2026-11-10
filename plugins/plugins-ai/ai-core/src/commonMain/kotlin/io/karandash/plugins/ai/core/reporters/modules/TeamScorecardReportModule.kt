package io.karandash.plugins.ai.core.reporters.modules

import io.karandash.api.PluginContext
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.reporters.HistoryAwareReportModule
import io.karandash.plugins.ai.core.reporters.TeamScorecardGenerator
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Report module wrapper for [TeamScorecardGenerator].
 *
 * Requires historical run records (via HISTORY input); skipped by the report runner when none exist.
 */
class TeamScorecardReportModule : HistoryAwareReportModule {
    override val id: String get() = ID
    override val name: String get() = "Team Scorecard"
    override val version: String get() = VERSION
    override val reportId: String get() = REPORT_ID
    override val reportName: String get() = "Team Scorecard"
    override val defaultEnabled: Boolean get() = false
    override val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.FINDINGS, ReportInput.HISTORY)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun generate(
        context: ReportContext,
        history: List<RunRecord>,
    ): ReportResult {
        val scorecard = TeamScorecardGenerator.generate(history)
        val formatted = TeamScorecardGenerator.formatReport(scorecard, LanguagePromptOverlays.responseLanguageOf(context.config))
        return ReportResult(
            reportId = REPORT_ID,
            data = JsonObject(
                mapOf(
                    "compositeScore" to JsonPrimitive(scorecard.compositeScore),
                    "totalRuns" to JsonPrimitive(scorecard.totalRuns),
                ),
            ),
            formatted = formatted,
            metrics = mapOf("team-score" to scorecard.compositeScore.toDouble()),
        )
    }

    companion object {
        const val ID = "ai-report-team-scorecard"
        const val REPORT_ID = "team-scorecard"
        private const val VERSION = "1.0.0"
    }
}
