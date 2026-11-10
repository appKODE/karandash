package io.karandash.plugins.ai.core.reporters

import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * AI prediction reporter: predicts which rules will fail before running them.
 *
 * Uses historical fire rates combined with MR characteristics to estimate
 * the probability of each rule producing findings. Boosts predictions for
 * large MRs (by lines of code) and wide-spread changes (by file count).
 */
object AiPredictionReporter {
    private const val MIN_HISTORY = 10
    private const val HIGH_PREDICTION = 0.7
    private const val MEDIUM_PREDICTION = 0.3
    private const val LARGE_MR_THRESHOLD = 500
    private const val LARGE_MR_BOOST = 1.5
    private const val MANY_FILES_THRESHOLD = 10
    private const val MANY_FILES_BOOST = 1.3
    private const val PERCENT = 100

    /**
     * Predicts which rules will produce findings for the given [mr].
     *
     * Returns an empty list when [history] contains fewer than [MIN_HISTORY] records.
     * Predictions are sorted by probability descending.
     *
     * @param mr The merge request to predict for.
     * @param history Historical run records.
     * @return List of [RulePrediction]s sorted by probability descending.
     */
    fun predict(
        mr: MergeRequest,
        history: List<RunRecord>,
    ): List<RulePrediction> {
        if (history.size < MIN_HISTORY) return emptyList()

        val baseRates = history.flatMap { it.findingsByRule.keys }.toSet().associateWith { rule ->
            history.count { (it.findingsByRule[rule] ?: 0) > 0 }.toDouble() / history.size
        }

        val totalLoc = mr.changes.sumOf { it.additions + it.deletions }
        val fileCount = mr.changes.size

        return baseRates
            .map { (rule, baseRate) ->
                var adjustedRate = baseRate
                if (totalLoc > LARGE_MR_THRESHOLD) adjustedRate *= LARGE_MR_BOOST
                if (fileCount > MANY_FILES_THRESHOLD) adjustedRate *= MANY_FILES_BOOST
                adjustedRate = adjustedRate.coerceIn(0.0, 1.0)

                RulePrediction(
                    ruleId = rule,
                    probability = adjustedRate,
                    confidence = when {
                        adjustedRate > HIGH_PREDICTION -> PredictionConfidence.HIGH
                        adjustedRate > MEDIUM_PREDICTION -> PredictionConfidence.MEDIUM
                        else -> PredictionConfidence.LOW
                    },
                )
            }.sortedByDescending { it.probability }
    }

    /**
     * Formats a list of [RulePrediction]s as a Markdown table.
     *
     * @param predictions Predictions to render.
     * @param language The `report.language` of the fixed texts; null renders English.
     * @return Markdown string with a table of rule, probability, and confidence columns.
     */
    fun formatReport(
        predictions: List<RulePrediction>,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.PREDICTION_TITLE.of(language))
            if (predictions.isEmpty()) {
                appendLine(AiReportText.PREDICTION_NONE.of(language))
            } else {
                appendLine(AiReportText.PREDICTION_HEADER.of(language))
                appendLine("|------|------------|------------|")
                for (pred in predictions) {
                    appendLine(
                        "| ${pred.ruleId} | ${(pred.probability * PERCENT).roundToInt()}% | ${AiReportText.label(
                            pred.confidence.name,
                            language
                        )} |"
                    )
                }
            }
        }
}

/**
 * Prediction for a single rule's likelihood of producing findings.
 *
 * @param ruleId Rule identifier.
 * @param probability Estimated probability (0.0–1.0) of this rule firing.
 * @param confidence Qualitative confidence band for the prediction.
 */
@Serializable
data class RulePrediction(
    val ruleId: String,
    val probability: Double,
    val confidence: PredictionConfidence,
)

/** Qualitative confidence band for a [RulePrediction]. */
enum class PredictionConfidence { LOW, MEDIUM, HIGH }
