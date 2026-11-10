package io.karandash.plugins.ai.core.metrics

import io.karandash.plugins.ai.core.config.CalibrationConfig
import io.karandash.plugins.ai.core.config.EvtAutoThresholdConfig
import io.karandash.plugins.ai.core.research.EvtAutoThreshold
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * AI-assisted calibration engine for deterministic rule thresholds.
 *
 * Analyzes historical validation results to determine whether rule thresholds
 * are too strict (too many findings = alert fatigue) or too lenient (too few = missing issues).
 *
 * Produces calibration recommendations that can be reviewed by the team.
 */
object CalibrationEngine {
    private const val HIGH_FIRE_RATE_THRESHOLD = 0.8
    private const val LOW_FIRE_RATE_THRESHOLD = 0.05
    private const val MIN_SAMPLES = 20
    private const val EVT_CONFIDENCE = 0.95
    private const val PERCENT = 100
    private const val SECONDS_PER_DAY = 86_400L

    /**
     * Analyzes historical run records and produces calibration recommendations.
     *
     * @param records Historical validation run records.
     * @param ruleIds Rules to analyze.
     * @param evtConfig `ai.research.evtAutoThreshold` when the user configured it; `null` keeps the
     *   fixed 95% confidence and 20-sample minimum.
     * @param highFireRate Fire rate above which a rule is recommended [CalibrationAction.LOOSEN] (default 80%).
     *   There is no labelled false-positive data, so `calibrate --target-fp-rate` passes its target here:
     *   the fire rate is the proxy for the false-positive rate.
     * @return List of calibration recommendations.
     */
    fun analyze(
        records: List<RunRecord>,
        ruleIds: Set<String>,
        evtConfig: EvtAutoThresholdConfig? = null,
        highFireRate: Double = HIGH_FIRE_RATE_THRESHOLD,
    ): List<CalibrationRecommendation> {
        if (records.size < (evtConfig?.minSamples ?: MIN_SAMPLES)) return emptyList()

        return ruleIds.mapNotNull { ruleId ->
            val fireCount = records.count { (it.findingsByRule[ruleId] ?: 0) > 0 }
            val fireRate = fireCount.toDouble() / records.size
            val avgFindings = records
                .map { it.findingsByRule[ruleId] ?: 0 }
                .average()

            when {
                fireRate > highFireRate -> {
                    val historicalValues = records.map { (it.findingsByRule[ruleId] ?: 0).toDouble() }
                    val evt = if (evtConfig == null) {
                        EvtAutoThreshold.computeThreshold(historicalValues, EVT_CONFIDENCE, avgFindings)
                    } else {
                        EvtAutoThreshold.computeThreshold(historicalValues, evtConfig.confidence.fraction, avgFindings, evtConfig)
                    }
                    CalibrationRecommendation(
                        ruleId = ruleId,
                        currentFireRate = fireRate,
                        averageFindingsPerRun = avgFindings,
                        recommendation = CalibrationAction.LOOSEN,
                        reason = "Rule fires on ${(fireRate * PERCENT).roundToInt()}% of MRs — threshold may be too strict",
                        suggestedThreshold = evt.threshold,
                    )
                }

                fireRate < LOW_FIRE_RATE_THRESHOLD && fireRate > 0.0 -> {
                    CalibrationRecommendation(
                        ruleId = ruleId,
                        currentFireRate = fireRate,
                        averageFindingsPerRun = avgFindings,
                        recommendation = CalibrationAction.TIGHTEN,
                        reason = "Rule fires on only ${(fireRate * PERCENT).roundToInt()}% of MRs — threshold may be too lenient",
                    )
                }

                fireRate == 0.0 -> {
                    CalibrationRecommendation(
                        ruleId = ruleId,
                        currentFireRate = 0.0,
                        averageFindingsPerRun = 0.0,
                        recommendation = CalibrationAction.REVIEW,
                        reason = "Rule never fires — may be misconfigured or unnecessary",
                    )
                }

                else -> {
                    null
                }
            }
        }
    }

    /**
     * `ai.calibration` report lines: a headline plus one line per rule needing attention. Empty when the
     * window holds too few runs to analyze.
     *
     * @param nowEpochSeconds Current time, for [CalibrationConfig.historyWindow].
     */
    fun report(
        records: List<RunRecord>,
        config: CalibrationConfig,
        nowEpochSeconds: Long,
        evtConfig: EvtAutoThresholdConfig? = null,
    ): List<String> {
        val cutoff = config.historyWindow?.let { nowEpochSeconds - it * SECONDS_PER_DAY }
        val window = records.filter { cutoff == null || it.timestamp >= cutoff }
        if (window.size < (evtConfig?.minSamples ?: MIN_SAMPLES)) return emptyList()
        val targets = config.rules
            .associate { it.ruleId to it.targetFalsePositiveRate }
            .ifEmpty { window.flatMap { it.findingsByRule.keys }.associateWith { null } }
        val lines = targets.flatMap { (ruleId, target) ->
            val limit = target ?: HIGH_FIRE_RATE_THRESHOLD
            analyze(window, setOf(ruleId), evtConfig, limit).map {
                "  ${it.ruleId}: fire rate ${(it.currentFireRate * PERCENT).roundToInt()}% " +
                    "(target ${(limit * PERCENT).roundToInt()}%) -> ${it.recommendation}: ${it.reason}"
            }
        }
        val headline = "Rule calibration report (${window.size} runs)"
        return listOf(headline) + lines.ifEmpty { listOf("  all rules within target") }
    }
}

/**
 * A calibration recommendation for a single rule.
 *
 * @param ruleId The rule identifier.
 * @param currentFireRate Fraction of runs [0.0, 1.0] on which this rule produced at least one finding.
 * @param averageFindingsPerRun Mean number of findings per run across all records.
 * @param recommendation Suggested calibration action.
 * @param reason Human-readable explanation of the recommendation.
 */
@Serializable
data class CalibrationRecommendation(
    val ruleId: String,
    val currentFireRate: Double,
    val averageFindingsPerRun: Double,
    val recommendation: CalibrationAction,
    val reason: String,
    /** EVT-derived suggested threshold when [recommendation] is [CalibrationAction.LOOSEN]; null otherwise. */
    val suggestedThreshold: Double? = null,
)

/** Suggested action from [CalibrationEngine.analyze]. */
enum class CalibrationAction {
    /** Rule fires too rarely - threshold may be too lenient. */
    TIGHTEN,

    /** Rule fires too often - threshold may be too strict. */
    LOOSEN,

    /** Rule never fires - configuration should be reviewed. */
    REVIEW,
}
