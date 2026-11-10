package io.karandash.api.finding

import kotlinx.serialization.Serializable

/**
 * Comparison against a previous validation baseline.
 *
 * Populated by the CLI `history` command when a baseline is available.
 * Reporters use this to show trend information (new vs resolved findings).
 *
 * @property baselineTimestamp Unix epoch seconds of the baseline report.
 * @property newFindings Number of findings not present in the baseline.
 * @property resolvedFindings Number of baseline findings no longer present.
 * @property unchangedFindings Number of findings present in both runs.
 */
@Serializable
public data class BaselineComparison(
    val baselineTimestamp: Long = 0,
    val newFindings: Int = 0,
    val resolvedFindings: Int = 0,
    val unchangedFindings: Int = 0,
)
