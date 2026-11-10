package io.karandash.api.finding

import kotlinx.serialization.Serializable

/**
 * External timing data supplied to [io.karandash.engine.ReportDataBuilder]
 * from the CLI pipeline.
 *
 * The builder combines this with per-rule timing from
 * [io.karandash.engine.ValidationResult.ruleExecutionResults] to produce
 * [ValidationMetrics].
 *
 * @property totalDurationMs Wall-clock time for the entire validation run.
 * @property enrichmentFetchTimeMs Time spent fetching enrichment data.
 * @property reportGenerationTimeMs Time spent generating the report.
 */
@Serializable
public data class TimingData(
    val totalDurationMs: Long = 0,
    val enrichmentFetchTimeMs: Long = 0,
    val reportGenerationTimeMs: Long = 0,
)
