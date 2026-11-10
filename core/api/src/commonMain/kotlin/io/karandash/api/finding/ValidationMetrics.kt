package io.karandash.api.finding

import kotlinx.serialization.Serializable

/**
 * Performance metrics captured during a validation run.
 *
 * All timing values are in milliseconds. Cache statistics reflect the
 * staleness gate results when rule-level caching is active.
 *
 * @property totalDurationMs Wall-clock time for the entire validation run.
 * @property ruleExecutionTimes Per-rule execution time in milliseconds, keyed by rule ID.
 * @property enrichmentFetchTimeMs Time spent fetching enrichment data from providers.
 * @property reportGenerationTimeMs Time spent building the [ValidationReport].
 * @property cacheHits Number of rules served from cache.
 * @property cacheMisses Number of rules that required fresh execution.
 */
@Serializable
public data class ValidationMetrics(
    val totalDurationMs: Long = 0,
    val ruleExecutionTimes: Map<String, Long> = emptyMap(),
    val enrichmentFetchTimeMs: Long = 0,
    val reportGenerationTimeMs: Long = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
)
