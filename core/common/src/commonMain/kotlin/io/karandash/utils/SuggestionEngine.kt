package io.karandash.utils

import io.karandash.normalize.ModuleIdNormalizer

/**
 * Suggests similar strings from candidates based on Levenshtein distance.
 *
 * Useful for providing "did you mean?" suggestions when users mistype
 * rule names, module IDs, or command names.
 */
object SuggestionEngine {
    /**
     * Suggests similar strings from candidates based on Levenshtein distance.
     *
     * Both sides are compared in their [ModuleIdNormalizer] form, so `JIRA_TICKET` matches `jira-ticket` and
     * `titleFormat` matches `title-format`.
     *
     * @param input The input string (potentially mistyped).
     * @param candidates List of valid options to compare against.
     * @return List of suggestions sorted by distance (closest first), max 3, distance <= 3.
     */
    fun suggest(
        input: String,
        candidates: List<String>,
    ): List<String> = ranked(input, candidates, DEFAULT_MAX_DISTANCE).take(MAX_SUGGESTIONS)

    /**
     * Find closest match from candidates (alias for [suggest]).
     */
    fun findClosest(
        input: String,
        candidates: List<String>,
    ): List<String> = suggest(input, candidates)

    /**
     * The single closest of [candidates] to [input] within [maxDistance] edits (compared like [suggest]), or null.
     * Ties go to the earlier candidate.
     */
    fun closest(
        input: String,
        candidates: Collection<String>,
        maxDistance: Int = DEFAULT_MAX_DISTANCE,
    ): String? = ranked(input, candidates, maxDistance).firstOrNull()

    private fun ranked(
        input: String,
        candidates: Collection<String>,
        maxDistance: Int,
    ): List<String> {
        val normalizedInput = ModuleIdNormalizer.normalize(input)
        return candidates
            .map { it to levenshteinDistance(normalizedInput, ModuleIdNormalizer.normalize(it)) }
            .filter { (_, distance) -> distance <= maxDistance }
            .sortedBy { (_, distance) -> distance }
            .map { (candidate, _) -> candidate }
    }

    private fun levenshteinDistance(
        s1: String,
        s2: String,
    ): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[len1][len2]
    }

    private const val DEFAULT_MAX_DISTANCE = 3
    private const val MAX_SUGGESTIONS = 3
}
