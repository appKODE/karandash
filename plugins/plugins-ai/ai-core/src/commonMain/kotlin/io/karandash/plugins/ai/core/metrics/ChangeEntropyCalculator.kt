package io.karandash.plugins.ai.core.metrics

import io.karandash.models.MergeRequest
import kotlinx.serialization.Serializable
import kotlin.math.ln

/**
 * Computes Shannon entropy of code change distributions.
 *
 * High entropy (changes spread uniformly across many files) indicates "shotgun surgery"
 * - scattered changes correlated with higher defect rates (Hassan, ICSE 2009; 82.5% AUROC).
 *
 * @see <a href="https://doi.org/10.1145/1555001.1555024">Hassan ICSE 2009</a>
 */
object ChangeEntropyCalculator {
    private const val LOG2_DIVISOR = 0.6931471805599453 // ln(2)

    /**
     * Computes the change entropy of an MR.
     *
     * @param mr The merge request to analyze.
     * @return [EntropyMetrics] with file-level and directory-level entropy.
     */
    fun calculate(mr: MergeRequest): EntropyMetrics {
        val fileChanges = mr.changes.map { it.additions + it.deletions }
        val fileEntropy = shannonEntropy(fileChanges)

        val dirChanges = mr.changes
            .groupBy { it.path.substringBeforeLast('/') }
            .map { (_, files) -> files.sumOf { it.additions + it.deletions } }
        val dirEntropy = shannonEntropy(dirChanges)

        val maxPossibleEntropy = if (mr.changes.size > 1) {
            log2(mr.changes.size.toDouble())
        } else {
            0.0
        }

        val normalizedEntropy = if (maxPossibleEntropy > 0) {
            fileEntropy / maxPossibleEntropy
        } else {
            0.0
        }

        return EntropyMetrics(
            fileEntropy = fileEntropy,
            directoryEntropy = dirEntropy,
            normalizedEntropy = normalizedEntropy,
            fileCount = mr.changes.size,
            directoryCount = dirChanges.size,
        )
    }

    /**
     * Computes Shannon entropy H = -Σ p_i * log2(p_i) for a distribution of change sizes.
     */
    internal fun shannonEntropy(values: List<Int>): Double {
        val total = values.sum()
        if (total == 0) return 0.0

        return -values
            .filter { it > 0 }
            .map { it.toDouble() / total }
            .sumOf { p -> p * log2(p) }
    }

    private fun log2(x: Double): Double = ln(x) / LOG2_DIVISOR
}

/**
 * Entropy metrics computed from the file change distribution of an MR.
 *
 * @param fileEntropy Shannon entropy across individual files; higher = more uniform spread.
 * @param directoryEntropy Shannon entropy across directories.
 * @param normalizedEntropy File entropy normalized to [0.0, 1.0] by maximum possible entropy.
 * @param fileCount Number of files changed.
 * @param directoryCount Number of distinct directories changed.
 */
@Serializable
data class EntropyMetrics(
    val fileEntropy: Double,
    val directoryEntropy: Double,
    val normalizedEntropy: Double,
    val fileCount: Int,
    val directoryCount: Int,
)
