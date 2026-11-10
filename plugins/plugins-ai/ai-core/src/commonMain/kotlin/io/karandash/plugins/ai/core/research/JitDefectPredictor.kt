package io.karandash.plugins.ai.core.research

import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.config.DefectPredictorConfig
import io.karandash.plugins.ai.core.config.DefectPredictorThresholdsConfig
import io.karandash.plugins.ai.core.config.DefectPredictorWeightsConfig
import kotlinx.serialization.Serializable

/**
 * Just-In-Time defect prediction using code and commit features.
 * Inspired by BiCC-BERT (JSS 2024): combines code change metrics with commit message quality.
 *
 * Produces a defect probability score in [0.0, 1.0] for each MR.
 *
 * Weights, risk thresholds, and scoring parameters are taken from [DefectPredictorConfig].
 * The default [predict] overload uses built-in defaults.
 */
object JitDefectPredictor {
    private const val LOW_COMMIT_RISK = 0.7
    private const val HIGH_COMMIT_RISK = 0.3
    private const val MISSING_DESCRIPTION_RISK = 0.8
    private const val HAS_DESCRIPTION_RISK = 0.2

    /**
     * Predicts the defect probability using default configuration.
     */
    fun predict(mr: MergeRequest): DefectPrediction = predict(mr, DefectPredictorConfig())

    /**
     * Predicts the defect probability for the given merge request.
     *
     * @param mr The merge request to evaluate.
     * @param config Configuration for weights and thresholds.
     * @return [DefectPrediction] with probability, risk level, and contributing factor scores.
     */
    fun predict(
        mr: MergeRequest,
        config: DefectPredictorConfig
    ): DefectPrediction {
        val weights = config.weights
        val thresholds = config.thresholds

        val totalLoc = mr.changes.sumOf { it.additions + it.deletions }
        val fileCount = mr.changes.size

        val sizeScore = (totalLoc.toDouble() / thresholds.largeMrLines).coerceIn(0.0, 1.0)
        val diffusionScore = (fileCount.toDouble() / thresholds.manyFilesCount).coerceIn(0.0, 1.0)

        val avgMessageLen = if (mr.commits.isNotEmpty()) {
            mr.commits.map { it.message.length }.average()
        } else {
            0.0
        }
        val purposeScore = if (avgMessageLen < thresholds.shortMessageChars) 1.0 else 0.0
        val historyScore = if (mr.commits.size <= 1) LOW_COMMIT_RISK else HIGH_COMMIT_RISK
        val reviewScore = if (mr.description.isNullOrBlank()) MISSING_DESCRIPTION_RISK else HAS_DESCRIPTION_RISK

        val probability = computeProbability(weights, sizeScore, diffusionScore, historyScore, purposeScore, reviewScore)

        return DefectPrediction(
            probability = probability,
            riskLevel = classifyRisk(probability, thresholds),
            factors = mapOf(
                "size" to sizeScore,
                "diffusion" to diffusionScore,
                "history" to historyScore,
                "purpose" to purposeScore,
                "review" to reviewScore,
            ),
        )
    }

    private fun computeProbability(
        w: DefectPredictorWeightsConfig,
        sizeScore: Double,
        diffusionScore: Double,
        historyScore: Double,
        purposeScore: Double,
        reviewScore: Double,
    ): Double =
        (
            sizeScore * w.size.fraction +
                diffusionScore * w.diffusion.fraction +
                historyScore * w.history.fraction +
                purposeScore * w.purpose.fraction +
                reviewScore * w.experience.fraction +
                (1.0 - reviewScore) * w.review.fraction
        ).coerceIn(0.0, 1.0)

    private fun classifyRisk(
        probability: Double,
        thresholds: DefectPredictorThresholdsConfig,
    ): DefectRiskLevel =
        when {
            probability > thresholds.highRisk.fraction -> DefectRiskLevel.HIGH
            probability > thresholds.mediumRisk.fraction -> DefectRiskLevel.MEDIUM
            else -> DefectRiskLevel.LOW
        }
}

/**
 * JIT defect prediction result for a merge request.
 *
 * @param probability Defect probability in [0.0, 1.0].
 * @param riskLevel Categorical risk level derived from [probability].
 * @param factors Map of contributing factor names to their individual scores.
 */
@Serializable
data class DefectPrediction(
    val probability: Double,
    val riskLevel: DefectRiskLevel,
    val factors: Map<String, Double>,
)

/** Categorical risk level for JIT defect prediction. */
enum class DefectRiskLevel { LOW, MEDIUM, HIGH }
