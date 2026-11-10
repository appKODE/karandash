package io.karandash.plugins.ai.core.metrics

import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import kotlinx.serialization.Serializable

/**
 * Hotspot analysis: identifies files that are both frequently changed (high churn)
 * and complex (high change volume), making them most defect-prone.
 *
 * Combines churn frequency with change complexity to produce a CRAP-like score.
 *
 * @see "Your Code as a Crime Scene" (Tornhill, 2015)
 */
object HotspotAnalyzer {
    private const val HIGH_HOTSPOT_THRESHOLD = 0.7
    private const val MEDIUM_HOTSPOT_THRESHOLD = 0.4
    private const val COMPLEXITY_NORMALIZATION_LINES = 100.0
    private const val CHURN_NORMALIZATION_LINES = 200.0
    private const val SCORE_DIVISOR = 2.0

    /**
     * Analyzes the MR to identify hotspot files.
     *
     * @param mr The merge request to analyze.
     * @param historicalChurn Map of file path to historical change count (from git log).
     *        When empty, uses only current MR change volume as a proxy.
     * @param complexitySaturationLines Changed lines at which a file's complexity score reaches 1.0.
     * @param churnSaturationLines Changed lines at which the churn proxy reaches 1.0 (no history).
     * @return List of hotspot entries sorted by score (highest first).
     */
    fun analyze(
        mr: MergeRequest,
        historicalChurn: Map<String, Int> = emptyMap(),
        complexitySaturationLines: Double = COMPLEXITY_NORMALIZATION_LINES,
        churnSaturationLines: Double = CHURN_NORMALIZATION_LINES,
    ): List<HotspotEntry> =
        mr.changes
            .map { change ->
                val normalizedComplexity = saturate(change, complexitySaturationLines)
                val normalizedChurn = if (historicalChurn.isNotEmpty()) {
                    val churn = historicalChurn[change.path]?.toDouble() ?: 0.0
                    val maxChurn = historicalChurn.values.maxOrNull()?.toDouble() ?: 1.0
                    (churn / maxChurn.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                } else {
                    saturate(change, churnSaturationLines)
                }
                val score = (normalizedComplexity + normalizedChurn) / SCORE_DIVISOR

                HotspotEntry(
                    path = change.path,
                    hotspotScore = score,
                    complexity = normalizedComplexity,
                    churn = normalizedChurn,
                    level = when {
                        score >= HIGH_HOTSPOT_THRESHOLD -> HotspotLevel.HIGH
                        score >= MEDIUM_HOTSPOT_THRESHOLD -> HotspotLevel.MEDIUM
                        else -> HotspotLevel.LOW
                    },
                )
            }.sortedByDescending { it.hotspotScore }

    /**
     * Scores a change by its size: changed lines over [saturationLines], capped at 1.0. Used as the complexity
     * estimate and, without history, as the churn estimate.
     */
    private fun saturate(
        change: FileChange,
        saturationLines: Double,
    ): Double {
        val totalLines = change.additions + change.deletions
        if (totalLines == 0) return 0.0
        return (totalLines.toDouble() / saturationLines.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
    }
}

/**
 * A single file's hotspot score within an MR.
 *
 * @param path File path relative to the repository root.
 * @param hotspotScore Combined score in [0.0, 1.0]; higher = more defect-prone.
 * @param complexity Normalized change complexity in [0.0, 1.0].
 * @param churn Normalized churn in [0.0, 1.0]; from historical data or estimated from change size.
 * @param level Categorical hotspot level.
 */
@Serializable
data class HotspotEntry(
    val path: String,
    val hotspotScore: Double,
    val complexity: Double,
    val churn: Double,
    val level: HotspotLevel,
)

/** Categorical hotspot level derived from the combined complexity + churn score. */
enum class HotspotLevel {
    LOW,
    MEDIUM,
    HIGH
}
