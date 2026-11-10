package io.karandash.plugins.ai.core

import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.config.ProcessRiskFactors
import kotlinx.serialization.Serializable

/**
 * Computes process risk metrics from MR data.
 *
 * These metrics are based on research showing that process metrics (churn, file
 * spread, commit patterns, author familiarity) outperform code complexity metrics
 * for defect prediction. All values are in [0.0, 1.0] where 1.0 = highest risk.
 */
object ProcessRiskCalculator {
    private const val CHURN_WEIGHT = 0.3
    private const val DIVERSITY_WEIGHT = 0.3
    private const val DENSITY_WEIGHT = 0.2
    private const val FAMILIARITY_WEIGHT = 0.2

    private const val FILE_DIVERSITY_SATURATION = 9.0
    private const val NEUTRAL_SCORE = 0.5

    /**
     * Computes a composite risk score from process metrics.
     *
     * @param mr The merge request to evaluate.
     * @param history Optional historical data about the author's past activity.
     * @param factors Factors counted in the composite; the weights of the enabled ones are rescaled to sum to 1.
     * @return [ProcessRiskMetrics] with individual scores and composite score in [0.0, 1.0].
     */
    fun calculate(
        mr: MergeRequest,
        history: MrHistory = MrHistory(),
        factors: ProcessRiskFactors = ProcessRiskFactors(),
    ): ProcessRiskMetrics {
        val churnRatio = calculateChurnRatio(mr)
        val fileDiversity = calculateFileDiversity(mr)
        val commitDensity = calculateCommitDensity(mr)
        val authorFamiliarity = calculateAuthorFamiliarity(mr, history)

        val weighted = listOf(
            Triple(factors.changeChurn, churnRatio, CHURN_WEIGHT),
            Triple(factors.fileDiversity, fileDiversity, DIVERSITY_WEIGHT),
            Triple(factors.changeDensity, commitDensity, DENSITY_WEIGHT),
            Triple(factors.authorFamiliarity, authorFamiliarity, FAMILIARITY_WEIGHT),
        ).filter { it.first }
        val totalWeight = weighted.sumOf { it.third }
        val composite = if (totalWeight > 0.0) {
            (weighted.sumOf { it.second * it.third } / totalWeight).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        return ProcessRiskMetrics(
            churnRatio = churnRatio,
            fileDiversity = fileDiversity,
            commitDensity = commitDensity,
            authorFamiliarity = authorFamiliarity,
            composite = composite,
        )
    }

    /**
     * Ratio of gross changes to net change. High add+delete relative to net = more churn = higher risk.
     */
    private fun calculateChurnRatio(mr: MergeRequest): Double {
        val additions = mr.changes.sumOf { it.additions }
        val deletions = mr.changes.sumOf { it.deletions }
        val total = additions + deletions
        if (total == 0) return 0.0
        val netChange = (additions - deletions).coerceAtLeast(1)
        return (total.toDouble() / (netChange * 2)).coerceIn(0.0, 1.0)
    }

    /**
     * How many different directories are touched. More directory spread = higher risk.
     * Normalized: 1 directory = 0.0, 10+ directories = 1.0.
     */
    private fun calculateFileDiversity(mr: MergeRequest): Double {
        if (mr.changes.isEmpty()) return 0.0
        val dirs = mr.changes.map { it.path.substringBeforeLast('/') }.toSet()
        return ((dirs.size - 1).toDouble() / FILE_DIVERSITY_SATURATION).coerceIn(0.0, 1.0)
    }

    /**
     * Commits per changed file. Few commits across many files (low ratio) = higher risk
     * because changes lack iterative refinement.
     */
    private fun calculateCommitDensity(mr: MergeRequest): Double {
        if (mr.commits.isEmpty() || mr.changes.isEmpty()) return NEUTRAL_SCORE
        val ratio = mr.commits.size.toDouble() / mr.changes.size
        return (1.0 - ratio.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
    }

    /**
     * Whether the author has previously touched these files. High familiarity = lower risk (inverted).
     * Without file history, the author's track record stands in: the share of their MRs not merged,
     * which is [NEUTRAL_SCORE] for the neutral [MrHistory.authorMergeSuccessRate].
     */
    private fun calculateAuthorFamiliarity(
        mr: MergeRequest,
        history: MrHistory
    ): Double {
        if (history.authorFileTouchCounts.isEmpty()) return (1.0 - history.authorMergeSuccessRate).coerceIn(0.0, 1.0)
        val touchedPaths = mr.changes.map { it.path }
        if (touchedPaths.isEmpty()) return NEUTRAL_SCORE
        val familiarCount = touchedPaths.count { path ->
            (history.authorFileTouchCounts[path] ?: 0) > 0
        }
        return 1.0 - (familiarCount.toDouble() / touchedPaths.size)
    }
}

/**
 * Process risk metrics computed from MR data without any AI calls.
 *
 * All scores are in [0.0, 1.0] where 1.0 represents the highest risk.
 *
 * @param churnRatio Ratio of gross line changes to net change; high = scattered edits.
 * @param fileDiversity Spread across directories; high = more cross-cutting change.
 * @param commitDensity Inverse of commits-per-file ratio; high = few commits across many files.
 * @param authorFamiliarity Inverse of file-familiarity rate; high = unfamiliar territory.
 * @param composite Weighted combination of all four scores.
 */
@Serializable
data class ProcessRiskMetrics(
    val churnRatio: Double,
    val fileDiversity: Double,
    val commitDensity: Double,
    val authorFamiliarity: Double,
    val composite: Double,
)

/**
 * Historical data about the MR author's past activity.
 *
 * [AuthorHistory] fills [authorMergeSuccessRate] from the platform's merge-request search.
 *
 * @param authorFileTouchCounts Map from file path to the number of times the author has changed it.
 * @param authorMergeSuccessRate Historical merge success rate for the author (0.0–1.0).
 */
@Serializable
data class MrHistory(
    val authorFileTouchCounts: Map<String, Int> = emptyMap(),
    val authorMergeSuccessRate: Double = NEUTRAL_MERGE_SUCCESS_RATE,
) {
    companion object {
        const val NEUTRAL_MERGE_SUCCESS_RATE = 0.5
    }
}
