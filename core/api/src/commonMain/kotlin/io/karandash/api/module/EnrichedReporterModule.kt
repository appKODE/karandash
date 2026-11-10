package io.karandash.api.module

import io.karandash.api.finding.ValidationReport
import kotlinx.serialization.json.JsonElement

/**
 * Extension of [ReporterModule] that receives a pre-computed [ValidationReport].
 *
 * Reporters implementing this interface receive all enriched data (summary, grouping,
 * compliance checklist, explanations) via the [reportEnriched] method, eliminating
 * the need for each reporter to compute these independently.
 *
 * The pipeline dispatches to [reportEnriched] when available, falling back to
 * [ReporterModule.report] for reporters that do not implement the enriched interface.
 */
public interface EnrichedReporterModule : ReporterModule {
    /**
     * Report validation results using a pre-computed [ValidationReport].
     *
     * @param report The fully-enriched validation report.
     * @param config Reporter-specific configuration as [JsonElement].
     * @return Optional output string (for file-based reporters).
     */
    public suspend fun reportEnriched(
        report: ValidationReport,
        config: JsonElement
    ): String?
}
