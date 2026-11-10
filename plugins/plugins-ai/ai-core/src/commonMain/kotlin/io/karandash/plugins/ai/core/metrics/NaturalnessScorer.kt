package io.karandash.plugins.ai.core.metrics

import io.karandash.models.FileChange
import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Software naturalness scoring based on token surprise.
 *
 * Code is more repetitive than natural language (lower cross-entropy).
 * Tokens with high "surprise" in the diff are likely bugs or unusual patterns.
 *
 * Uses a simple trigram model trained on the MR's own context (self-supervised).
 *
 * @see "On the Naturalness of Software" (Hindle et al., ICSE 2012)
 * @see "Syntax and Sensibility" (FSE 2018)
 */
object NaturalnessScorer {
    private const val NGRAM_SIZE = 3
    private const val MIN_TOKENS = 20
    private const val HIGH_SURPRISE_STDDEV = 2.0

    /**
     * Scores the naturalness of added code in each changed file.
     *
     * @param changes List of file changes from the MR.
     * @return List of naturalness scores per file, sorted by average surprise descending.
     */
    fun score(changes: List<FileChange>): List<NaturalnessEntry> {
        val contextTokens = changes
            .mapNotNull { it.diffContent }
            .flatMap { extractContextTokens(it) }

        if (contextTokens.size < MIN_TOKENS) return emptyList()

        val model = buildTrigramModel(contextTokens)

        return changes
            .mapNotNull { change ->
                val addedTokens = extractAddedTokens(change.diffContent ?: return@mapNotNull null)
                if (addedTokens.size < NGRAM_SIZE) return@mapNotNull null

                val surpriseScores = computeSurpriseScores(addedTokens, model)
                if (surpriseScores.isEmpty()) return@mapNotNull null

                val avgSurprise = surpriseScores.average()
                val maxSurprise = surpriseScores.max()
                val stdDev = standardDeviation(surpriseScores)
                val highSurpriseCount = surpriseScores.count { it > avgSurprise + HIGH_SURPRISE_STDDEV * stdDev }

                NaturalnessEntry(
                    path = change.path,
                    averageSurprise = avgSurprise,
                    maxSurprise = maxSurprise,
                    highSurpriseTokenCount = highSurpriseCount,
                    totalTokens = addedTokens.size,
                )
            }.sortedByDescending { it.averageSurprise }
    }

    /**
     * Builds a trigram language model mapping bigram context to next-token counts.
     *
     * @param tokens Flat token list to train on.
     * @return Map of "token_i token_{i+1}" -> Map of token_{i+2} -> count.
     */
    internal fun buildTrigramModel(tokens: List<String>): Map<String, Map<String, Int>> {
        val model = mutableMapOf<String, MutableMap<String, Int>>()
        for (i in 0..tokens.size - NGRAM_SIZE) {
            val context = tokens[i] + " " + tokens[i + 1]
            val next = tokens[i + 2]
            model
                .getOrPut(context) { mutableMapOf() }
                .let { inner -> inner[next] = (inner[next] ?: 0) + 1 }
        }
        return model
    }

    private fun computeSurpriseScores(
        tokens: List<String>,
        model: Map<String, Map<String, Int>>,
    ): List<Double> {
        if (tokens.size < NGRAM_SIZE) return emptyList()

        return (0..tokens.size - NGRAM_SIZE).map { i ->
            val context = tokens[i] + " " + tokens[i + 1]
            val actual = tokens[i + 2]
            val distribution = model[context]

            if (distribution == null) {
                1.0
            } else {
                val total = distribution.values.sum()
                val count = distribution[actual] ?: 0
                if (count == 0) {
                    1.0
                } else {
                    -ln(count.toDouble() / total)
                }
            }
        }
    }

    private fun extractContextTokens(diff: String): List<String> =
        diff
            .lines()
            .filter { !it.startsWith("@@") && !it.startsWith("---") && !it.startsWith("+++") }
            .flatMap { tokenize(it.removePrefix("+").removePrefix("-").removePrefix(" ")) }

    private fun extractAddedTokens(diff: String): List<String> =
        diff
            .lines()
            .filter { it.startsWith("+") && !it.startsWith("+++") }
            .flatMap { tokenize(it.removePrefix("+")) }

    /**
     * Splits a source code line into tokens on whitespace and common operators.
     *
     * @param line A single line of source code (stripped of diff prefix).
     * @return Non-blank token strings.
     */
    internal fun tokenize(line: String): List<String> =
        line
            .split(Regex("[\\s(){}\\[\\];,.<>:=+\\-*/&|^~!?@#$%]+"))
            .filter { it.isNotBlank() }

    private fun standardDeviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance)
    }
}

/**
 * Naturalness score for a single changed file.
 *
 * @param path File path relative to repo root.
 * @param averageSurprise Mean negative log-probability across all scored trigrams (lower = more natural).
 * @param maxSurprise Highest surprise score observed in this file.
 * @param highSurpriseTokenCount Number of tokens whose surprise exceeds mean + 2σ.
 * @param totalTokens Total number of added tokens analyzed.
 */
@Serializable
data class NaturalnessEntry(
    val path: String,
    val averageSurprise: Double,
    val maxSurprise: Double,
    val highSurpriseTokenCount: Int,
    val totalTokens: Int,
)
