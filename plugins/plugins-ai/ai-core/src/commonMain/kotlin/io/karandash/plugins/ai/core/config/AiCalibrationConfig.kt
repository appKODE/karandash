package io.karandash.plugins.ai.core.config

import kotlinx.serialization.Serializable

/**
 * `ai.calibration`: logs a rule calibration report from the `ai.statistics` run history at the end of each run.
 *
 * @param enabled `false` turns the report off.
 * @param historyWindow Only runs recorded in the last N days count; `null` = every stored run.
 * @param rules Rules to report on with their own target; empty = every rule seen in the history.
 */
@Serializable
data class CalibrationConfig(
    val enabled: Boolean = true,
    val historyWindow: Int? = null,
    val rules: List<CalibrationRuleTarget> = emptyList(),
)

/**
 * A rule and the fire rate it should stay under.
 *
 * @param targetFalsePositiveRate Fire rate (0–1) above which the rule is reported LOOSEN; no labelled outcomes
 *   are recorded, so the fire rate stands in for the false-positive rate. `null` = the default 80%.
 */
@Serializable
data class CalibrationRuleTarget(
    val ruleId: String,
    val targetFalsePositiveRate: Double? = null,
)

/**
 * `ai.sandbox`: defaults for `karandash test-rule`.
 *
 * @param historyWindow Recorded runs replayed in history mode when `--replay-count` is not given.
 * @param reportMetrics Also prints the severity distribution and affected-MR count.
 */
@Serializable
data class SandboxConfig(
    val historyWindow: Int? = null,
    val reportMetrics: Boolean = false,
)
