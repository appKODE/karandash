package io.karandash.plugins.ai.core.config

import io.karandash.percent.KarandashPercent
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Weights for JIT defect predictor factors.
 *
 * All weights must sum to 100% (within 1% tolerance).
 */
@Serializable
data class DefectPredictorWeightsConfig(
    val size: KarandashPercent = DEFAULT_SIZE,
    val diffusion: KarandashPercent = DEFAULT_DIFFUSION,
    val history: KarandashPercent = DEFAULT_HISTORY,
    val purpose: KarandashPercent = DEFAULT_PURPOSE,
    val experience: KarandashPercent = DEFAULT_EXPERIENCE,
    val review: KarandashPercent = DEFAULT_REVIEW,
) {
    init {
        val sum = size.fraction + diffusion.fraction + history.fraction +
            purpose.fraction + experience.fraction + review.fraction
        require(abs(sum - 1.0) <= TOLERANCE) {
            "Defect predictor weights must sum to 100% (got ${(sum * PERCENT).roundToInt()}%)"
        }
    }

    companion object {
        private const val TOLERANCE = 0.01
        private const val PERCENT = 100.0
        private const val DEFAULT_SIZE_PERCENT = 20
        private const val DEFAULT_DIFFUSION_PERCENT = 20
        private const val DEFAULT_MINOR_PERCENT = 15
        val DEFAULT_SIZE: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_SIZE_PERCENT)
        val DEFAULT_DIFFUSION: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_DIFFUSION_PERCENT)
        val DEFAULT_HISTORY: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_MINOR_PERCENT)
        val DEFAULT_PURPOSE: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_MINOR_PERCENT)
        val DEFAULT_EXPERIENCE: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_MINOR_PERCENT)
        val DEFAULT_REVIEW: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_MINOR_PERCENT)
    }
}

/**
 * Thresholds for JIT defect predictor risk classification and scoring.
 *
 * @property highRisk Probability above which an MR is classified as high risk.
 * @property mediumRisk Probability above which an MR is classified as medium risk.
 * @property largeMrLines Line count threshold for the size factor normalisation.
 * @property manyFilesCount File count threshold for the diffusion factor normalisation.
 * @property shortMessageChars Commit message length below which purpose score is 1.0.
 */
@Serializable
data class DefectPredictorThresholdsConfig(
    val highRisk: KarandashPercent = DEFAULT_HIGH_RISK,
    val mediumRisk: KarandashPercent = DEFAULT_MEDIUM_RISK,
    val largeMrLines: Int = DEFAULT_LARGE_MR_LINES,
    val manyFilesCount: Int = DEFAULT_MANY_FILES_COUNT,
    val shortMessageChars: Int = DEFAULT_SHORT_MESSAGE_CHARS,
) {
    companion object {
        private const val DEFAULT_HIGH_RISK_PERCENT = 70
        private const val DEFAULT_MEDIUM_RISK_PERCENT = 40
        val DEFAULT_HIGH_RISK: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_HIGH_RISK_PERCENT)
        val DEFAULT_MEDIUM_RISK: KarandashPercent = KarandashPercent.ofPercent(DEFAULT_MEDIUM_RISK_PERCENT)
        const val DEFAULT_LARGE_MR_LINES = 500
        const val DEFAULT_MANY_FILES_COUNT = 20
        const val DEFAULT_SHORT_MESSAGE_CHARS = 10
    }
}

/**
 * Configuration for the JIT defect predictor.
 *
 * ```yaml
 * ai:
 *   defect-predictor:
 *     weights:
 *       size: "25%"
 *       diffusion: "25%"
 *     thresholds:
 *       high-risk: "80%"
 *       large-mr-lines: 300
 * ```
 */
@Serializable
data class DefectPredictorConfig(
    val weights: DefectPredictorWeightsConfig = DefectPredictorWeightsConfig(),
    val thresholds: DefectPredictorThresholdsConfig = DefectPredictorThresholdsConfig(),
)
