package io.karandash.plugins.ai.core.research

import io.karandash.plugins.ai.core.config.EvtAutoThresholdConfig
import kotlinx.serialization.Serializable
import kotlin.math.ln

/**
 * Extreme Value Theory (EVT) for data-driven threshold estimation.
 *
 * Instead of hardcoding thresholds (max-lines: 500), derives them from historical data.
 * Fits a simplified Generalized Pareto Distribution to tail values and estimates
 * the return level at a chosen confidence.
 *
 * Parameters are configurable via [EvtAutoThresholdConfig].
 */
object EvtAutoThreshold {
    /**
     * Computes an auto-threshold using default configuration.
     */
    fun computeThreshold(
        historicalValues: List<Double>,
        confidence: Double = EvtAutoThresholdConfig().confidence.fraction,
        fallback: Double,
    ): EvtThresholdResult = computeThreshold(historicalValues, confidence, fallback, EvtAutoThresholdConfig())

    /**
     * Computes an auto-threshold from historical values.
     *
     * @param historicalValues List of historical metric values (e.g., MR sizes).
     * @param confidence Confidence level (0.0-1.0). Higher = more permissive threshold.
     * @param fallback Fallback threshold when insufficient data.
     * @param config Configuration for min samples and tail fraction.
     * @return [EvtThresholdResult] with the computed threshold and metadata.
     */
    fun computeThreshold(
        historicalValues: List<Double>,
        confidence: Double,
        fallback: Double,
        config: EvtAutoThresholdConfig,
    ): EvtThresholdResult {
        if (historicalValues.size < config.minSamples) {
            return EvtThresholdResult(
                threshold = fallback,
                isDataDriven = false,
                sampleSize = historicalValues.size,
                confidence = confidence,
            )
        }

        val sorted = historicalValues.sorted()
        val n = sorted.size

        val percentileIdx = (n * confidence).toInt().coerceIn(0, n - 1)
        val percentileValue = sorted[percentileIdx]

        // Tail analysis: compute mean excess over the tail threshold.
        val tailStart = (n * (1.0 - config.tailFraction.fraction)).toInt().coerceIn(0, n - 1)
        val tailThreshold = sorted[tailStart]
        val exceedances = sorted.drop(tailStart).map { it - tailThreshold }
        val meanExcess = if (exceedances.isNotEmpty()) exceedances.average() else 0.0

        // Estimated return level: threshold + mean excess * scale factor derived from confidence.
        val scaleFactor = -ln(1.0 - confidence)
        val returnLevel = tailThreshold + meanExcess * scaleFactor

        return EvtThresholdResult(
            threshold = maxOf(percentileValue, returnLevel),
            isDataDriven = true,
            sampleSize = n,
            confidence = confidence,
            percentileValue = percentileValue,
            returnLevel = returnLevel,
        )
    }
}

/**
 * Result of an EVT threshold computation.
 *
 * @param threshold The computed (or fallback) threshold value.
 * @param isDataDriven True when enough data was available to compute a data-driven threshold.
 * @param sampleSize Number of historical values supplied.
 * @param confidence The confidence level used.
 * @param percentileValue The raw percentile value at the requested confidence.
 * @param returnLevel The GPD-inspired return level estimate.
 */
@Serializable
data class EvtThresholdResult(
    val threshold: Double,
    val isDataDriven: Boolean,
    val sampleSize: Int,
    val confidence: Double,
    val percentileValue: Double = 0.0,
    val returnLevel: Double = 0.0,
)
