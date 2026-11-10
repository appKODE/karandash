package io.karandash.api.ai

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.context.EnrichmentContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Generates a report from validation findings and optional historical data.
 *
 * Reports run post-validation: summaries, scorecards, heatmaps, etc.
 *
 * Lifecycle: [Module.initialize] -> [generate] (once per run) -> [Module.shutdown].
 * Failures are non-fatal: logged and skipped, validation continues with
 * whatever reports were successfully generated.
 */
public interface AiReportModule : Module {
    override val type: ModuleType get() = ModuleType.AI_REPORT

    /** Unique report identifier (e.g. "scorecard", "heatmap"). */
    public val reportId: String

    /** Human-readable report name for CLI help and documentation. */
    public val reportName: String

    /**
     * Declares which input categories this report requires.
     *
     * The pipeline uses this to skip reports when required inputs are unavailable,
     * rather than invoking the report only to fail.
     * Default: findings only.
     */
    public val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.FINDINGS)

    /**
     * Generate the report from the given context.
     *
     * @param context Full report context including findings, MR, enrichment, and config.
     * @return The generated report with formatted output and optional metrics.
     */
    public suspend fun generate(context: ReportContext): ReportResult
}

/** Categories of input data that a report or metric may consume. */
public enum class ReportInput {
    FINDINGS,
    HISTORY,
    MERGE_REQUEST,
    ENRICHMENT,
}

/**
 * Context passed to [AiReportModule.generate] and [AiMetricModule.compute].
 *
 * Bundles all parameters into a single object so new context can be added
 * without breaking existing signatures.
 *
 * Not @Serializable: runtime context holder, carries the live [EnrichmentContext].
 */
public data class ReportContext(
    /** Validation findings from the current run. */
    val findings: List<io.karandash.models.finding.Finding>,
    /** The merge request that was validated. */
    val mr: io.karandash.models.MergeRequest,
    /** Enrichment data collected during the pipeline. */
    val enrichment: EnrichmentContext,
    /** Report/metric-specific configuration from the user's config file. */
    val config: JsonElement,
    /**
     * Recorded past runs, oldest-first, as the run-statistics store serializes them; empty when none are recorded.
     * [ReportInput.HISTORY] is available exactly when this is non-empty.
     */
    val history: List<JsonElement> = emptyList(),
)

/**
 * Result of a single report generation.
 *
 * Contains both structured data ([data]) for programmatic consumption
 * and a pre-formatted string ([formatted]) for display.
 */
@Serializable
public data class ReportResult(
    /** Report identifier matching [AiReportModule.reportId]. */
    val reportId: String,
    /** Structured report data as JSON for programmatic consumption. */
    val data: JsonElement,
    /** Pre-formatted report output for display (Markdown, plain text, etc.). */
    val formatted: String,
    /** Optional numeric metrics extracted during report generation. */
    val metrics: Map<String, Double> = emptyMap(),
)
