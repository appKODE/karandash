package io.karandash.plugins.ai.core.metrics

import io.karandash.models.MergeRequest
import kotlinx.serialization.Serializable

/**
 * Estimates cognitive load for code review based on research-backed thresholds.
 *
 * Evidence:
 * - >200 LOC: review quality degrades measurably
 * - >400 LOC: reviewers shift to style-only comments
 * - >450 LOC/hr: 87% of defects missed (Baldawa 2024)
 * - >60 min: cognitive fatigue
 *
 * @see <a href="https://smartbear.com/learn/code-review/best-practices-for-peer-code-review/">Cisco Code Review Study</a>
 */
object CognitiveLoadEstimator {
    private const val LOC_THRESHOLD_LOW = 200
    private const val LOC_THRESHOLD_HIGH = 400
    private const val MAX_REVIEW_MINUTES = 60
    private const val LOC_PER_MINUTE = 7.5
    private const val DIVERSITY_WEIGHT = 2.0
    private const val MIN_REVIEW_MINUTES = 1.0

    /**
     * Estimates the cognitive load of reviewing an MR.
     *
     * @param mr The merge request to analyze.
     * @return [CognitiveLoadMetrics] with LOC counts, estimated review time, and risk level.
     */
    fun estimate(mr: MergeRequest): CognitiveLoadMetrics {
        val totalLoc = mr.changes.sumOf { it.additions + it.deletions }
        val fileCount = mr.changes.size

        val dirCount = mr.changes
            .map { it.path.substringBeforeLast('/') }
            .toSet()
            .size
        val extCount = mr.changes
            .map { it.path.substringAfterLast('.') }
            .toSet()
            .size

        val diversityFactor = 1.0 + (dirCount - 1).coerceAtLeast(0) * (DIVERSITY_WEIGHT / 10.0)
        val effectiveLoc = (totalLoc * diversityFactor).toInt()

        val estimatedMinutes = (effectiveLoc / LOC_PER_MINUTE).coerceAtLeast(MIN_REVIEW_MINUTES)

        val riskLevel = when {
            totalLoc > LOC_THRESHOLD_HIGH -> CognitiveRiskLevel.HIGH
            totalLoc > LOC_THRESHOLD_LOW -> CognitiveRiskLevel.MEDIUM
            else -> CognitiveRiskLevel.LOW
        }

        return CognitiveLoadMetrics(
            totalLoc = totalLoc,
            effectiveLoc = effectiveLoc,
            fileCount = fileCount,
            directoryCount = dirCount,
            conceptDiversity = extCount,
            estimatedReviewMinutes = estimatedMinutes,
            exceedsRecommendedTime = estimatedMinutes > MAX_REVIEW_MINUTES,
            riskLevel = riskLevel,
            recommendation = generateRecommendation(totalLoc, estimatedMinutes, fileCount),
        )
    }

    private fun generateRecommendation(
        totalLoc: Int,
        estimatedMinutes: Double,
        fileCount: Int,
    ): String? =
        when {
            totalLoc > LOC_THRESHOLD_HIGH -> {
                "Consider splitting: $totalLoc LOC exceeds the 400-LOC threshold where 87% of defects are missed"
            }

            estimatedMinutes > MAX_REVIEW_MINUTES -> {
                "Review may take ~${estimatedMinutes.toInt()} minutes — consider breaking into sessions"
            }

            totalLoc > LOC_THRESHOLD_LOW && fileCount > 10 -> {
                "Large MR touching $fileCount files — focus review on high-risk areas first"
            }

            else -> {
                null
            }
        }
}

/**
 * Cognitive load metrics estimated from MR change data.
 *
 * @param totalLoc Raw line count (additions + deletions).
 * @param effectiveLoc LOC adjusted for directory diversity.
 * @param fileCount Number of changed files.
 * @param directoryCount Number of distinct directories touched.
 * @param conceptDiversity Number of distinct file extensions - a proxy for conceptual breadth.
 * @param estimatedReviewMinutes Projected review time in minutes.
 * @param exceedsRecommendedTime Whether [estimatedReviewMinutes] exceeds 60 minutes.
 * @param riskLevel Categorical risk level derived from [totalLoc] thresholds.
 * @param recommendation Human-readable suggestion, or null when the MR is within safe bounds.
 */
@Serializable
data class CognitiveLoadMetrics(
    val totalLoc: Int,
    val effectiveLoc: Int,
    val fileCount: Int,
    val directoryCount: Int,
    val conceptDiversity: Int,
    val estimatedReviewMinutes: Double,
    val exceedsRecommendedTime: Boolean,
    val riskLevel: CognitiveRiskLevel,
    val recommendation: String?,
)

/** Categorical risk level based on LOC thresholds from code review research. */
enum class CognitiveRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
