package io.karandash.plugins.ai.core.research

import kotlinx.serialization.Serializable

/**
 * Adaptive defect prediction with concept drift handling.
 * Inspired by CodeFlowLM (2025): maintains running statistics that adapt to changing codebases.
 *
 * Uses exponential weighted moving average (EWMA) to track feature distributions
 * and detect when the codebase's defect patterns shift.
 *
 * Cross-run statistics live in [DefectModelState]: build the model from the persisted state,
 * call [update], then persist [snapshot].
 *
 * @lifecycle per-run -- do not reuse across invocations
 *
 * @param decayFactor EWMA decay weight applied to the existing mean and variance (0.0–1.0).
 *   Values closer to 1.0 give more weight to history; values closer to 0.0 adapt faster.
 * @param initial Statistics carried over from earlier runs.
 */
class AdaptiveDefectModel(
    private val decayFactor: Double = DEFAULT_DECAY,
    initial: DefectModelState = DefectModelState(),
) {
    private var runningMean: Double = initial.mean
    private var runningVariance: Double = initial.variance
    private var sampleCount: Int = initial.sampleCount

    /**
     * Updates the model with a new observation and returns whether concept drift is detected.
     *
     * Drift is signalled when the new observation deviates more than [DRIFT_THRESHOLD] standard
     * deviations from the running mean, provided at least [MIN_SAMPLES_FOR_DRIFT] samples have
     * been seen.
     *
     * @param defectScore A defect score observation in [0.0, 1.0].
     * @return [DriftDetection] describing whether drift was detected and the updated statistics.
     */
    fun update(defectScore: Double): DriftDetection {
        sampleCount++
        if (sampleCount == 1) {
            runningMean = defectScore
            runningVariance = 0.0
            return DriftDetection(driftDetected = false, newMean = runningMean, deviation = 0.0)
        }

        val oldMean = runningMean
        runningMean = decayFactor * runningMean + (1.0 - decayFactor) * defectScore
        runningVariance = decayFactor * runningVariance +
            (1.0 - decayFactor) * (defectScore - oldMean) * (defectScore - runningMean)

        val stdDev = kotlin.math.sqrt(runningVariance.coerceAtLeast(0.0))
        val deviation = if (stdDev > 0) kotlin.math.abs(defectScore - runningMean) / stdDev else 0.0
        val driftDetected = deviation > DRIFT_THRESHOLD && sampleCount >= MIN_SAMPLES_FOR_DRIFT

        return DriftDetection(
            driftDetected = driftDetected,
            newMean = runningMean,
            deviation = deviation,
        )
    }

    /** Returns the current EWMA mean. */
    fun currentMean(): Double = runningMean

    /** Returns the total number of observations processed so far. */
    fun sampleCount(): Int = sampleCount

    /** Current statistics, for persistence across runs. */
    fun snapshot(): DefectModelState = DefectModelState(runningMean, runningVariance, sampleCount)

    companion object {
        private const val DEFAULT_DECAY = 0.95
        private const val DRIFT_THRESHOLD = 3.0
        private const val MIN_SAMPLES_FOR_DRIFT = 10
    }
}

/**
 * Persisted EWMA statistics of [AdaptiveDefectModel].
 *
 * @param mean Running mean.
 * @param variance Running variance.
 * @param sampleCount Observations seen so far.
 */
@Serializable
data class DefectModelState(
    val mean: Double = 0.0,
    val variance: Double = 0.0,
    val sampleCount: Int = 0,
)

/**
 * Result of a single [AdaptiveDefectModel.update] call.
 *
 * @param driftDetected True when the observation deviates significantly from the running mean.
 * @param newMean Updated EWMA mean after incorporating the observation.
 * @param deviation Deviation of the observation in standard-deviation units.
 */
@Serializable
data class DriftDetection(
    val driftDetected: Boolean,
    val newMean: Double,
    val deviation: Double,
)
