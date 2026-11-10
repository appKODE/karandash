package io.karandash.api.ai

import io.karandash.api.Module
import io.karandash.api.ModuleType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Computes a single metric from validation data.
 *
 * Metrics are lightweight - a numeric value plus metadata.
 * They run post-validation alongside reports and are collected
 * into the [ReportEngineResult][io.karandash.engine.ReportEngineResult].
 *
 * Lifecycle: [Module.initialize] -> [compute] (once per run) -> [Module.shutdown].
 * Failures are non-fatal: logged and skipped.
 */
public interface AiMetricModule : Module {
    override val type: ModuleType get() = ModuleType.AI_METRIC

    /** Unique metric identifier (e.g. "complexity-score", "risk-index"). */
    public val metricId: String

    /** Human-readable metric name for CLI help and documentation. */
    public val metricName: String

    /** Unit of measurement (e.g. "score", "percent", "count"). */
    public val unit: String

    /**
     * Declares which input categories this metric requires.
     *
     * Default: findings only.
     */
    public val requiredInputs: Set<ReportInput> get() = setOf(ReportInput.FINDINGS)

    /**
     * Compute the metric from the given context.
     *
     * @param context Full report context including findings, MR, enrichment, and config.
     * @return The computed metric value with metadata.
     */
    public suspend fun compute(context: ReportContext): MetricResult
}

/**
 * Result of a single metric computation.
 */
@Serializable
public data class MetricResult(
    /** Metric identifier matching [AiMetricModule.metricId]. */
    val metricId: String,
    /** Computed numeric value. */
    val value: Double,
    /** Unit of measurement. */
    val unit: String,
    /** Optional human-readable label (e.g. "High Risk", "A+"). */
    val label: String = "",
    /** Optional structured metadata for downstream consumers. */
    val metadata: Map<String, JsonElement> = emptyMap(),
)
