package io.karandash.multipattern

import kotlinx.serialization.Serializable

/**
 * Multi-pattern matcher using Aho-Corasick automaton.
 *
 * Matches many patterns against text in a single pass - O(n + m) where
 * n = text length, m = total pattern length. Achieves Hyperscan-like
 * throughput for security scanning without actual SIMD instructions.
 *
 * Usage:
 * ```kotlin
 * val matcher = MultiPatternMatcher.build(listOf("password", "secret", "api_key"))
 * val matches = matcher.findAll("my password is secret")
 * // matches = [PatternMatch(pattern="password", position=3), PatternMatch(pattern="secret", position=15)]
 * ```
 */
class MultiPatternMatcher private constructor(
    private val goto: Array<IntArray>,
    private val fail: IntArray,
    private val output: Array<List<String>>,
) {
    /**
     * Finds all pattern matches in the given text.
     *
     * @param text The text to search.
     * @return List of matches with pattern and position.
     */
    fun findAll(text: String): List<PatternMatch> {
        val matches = mutableListOf<PatternMatch>()
        var state = 0

        for ((i, ch) in text.withIndex()) {
            val c = ch.code.coerceIn(0, ALPHABET_SIZE - 1)
            while (state != 0 && goto[state][c] == FAIL_STATE) {
                state = fail[state]
            }
            state = if (goto[state][c] != FAIL_STATE) goto[state][c] else 0

            for (pattern in output[state]) {
                matches.add(PatternMatch(pattern = pattern, position = i - pattern.length + 1))
            }
        }

        return matches
    }

    /**
     * Checks if any pattern matches in the text (short-circuits on first match).
     *
     * @param text The text to search.
     * @return `true` if at least one pattern is found.
     */
    fun containsAny(text: String): Boolean {
        var state = 0
        for (ch in text) {
            val c = ch.code.coerceIn(0, ALPHABET_SIZE - 1)
            while (state != 0 && goto[state][c] == FAIL_STATE) {
                state = fail[state]
            }
            state = if (goto[state][c] != FAIL_STATE) goto[state][c] else 0
            if (output[state].isNotEmpty()) return true
        }
        return false
    }

    /** Number of distinct patterns in the matcher. */
    fun patternCount(): Int = output.flatMapTo(mutableSetOf()) { it }.size

    companion object {
        private const val ALPHABET_SIZE = 128 // ASCII
        private const val FAIL_STATE = -1

        /**
         * Builds an Aho-Corasick automaton from the given patterns.
         *
         * @param patterns List of patterns to match. Duplicate patterns are allowed.
         * @return A compiled matcher ready for searching.
         */
        fun build(patterns: List<String>): MultiPatternMatcher {
            if (patterns.isEmpty()) {
                return MultiPatternMatcher(
                    goto = arrayOf(IntArray(ALPHABET_SIZE) { FAIL_STATE }),
                    fail = intArrayOf(0),
                    output = arrayOf(emptyList()),
                )
            }

            val maxStates = patterns.sumOf { it.length } + 1
            val gotoFn = Array(maxStates) { IntArray(ALPHABET_SIZE) { FAIL_STATE } }
            val outputFn = Array(maxStates) { mutableListOf<String>() }
            var stateCount = 1

            // Build goto function
            for (pattern in patterns) {
                var state = 0
                for (ch in pattern) {
                    val c = ch.code.coerceIn(0, ALPHABET_SIZE - 1)
                    if (gotoFn[state][c] == FAIL_STATE) {
                        gotoFn[state][c] = stateCount++
                    }
                    state = gotoFn[state][c]
                }
                outputFn[state].add(pattern)
            }

            // Build fail function using BFS
            val failFn = IntArray(stateCount)
            val queue = ArrayDeque<Int>()

            for (c in 0 until ALPHABET_SIZE) {
                val s = gotoFn[0][c]
                if (s != FAIL_STATE && s != 0) {
                    failFn[s] = 0
                    queue.add(s)
                }
            }

            while (queue.isNotEmpty()) {
                val r = queue.removeFirst()
                for (c in 0 until ALPHABET_SIZE) {
                    val s = gotoFn[r][c]
                    if (s == FAIL_STATE) continue
                    queue.add(s)
                    var state = failFn[r]
                    while (state != 0 && gotoFn[state][c] == FAIL_STATE) {
                        state = failFn[state]
                    }
                    failFn[s] = if (gotoFn[state][c] != FAIL_STATE && gotoFn[state][c] != s) {
                        gotoFn[state][c]
                    } else {
                        0
                    }
                    outputFn[s].addAll(outputFn[failFn[s]])
                }
            }

            return MultiPatternMatcher(
                goto = gotoFn.take(stateCount).toTypedArray(),
                fail = failFn,
                output = outputFn.take(stateCount).map { it.toList() }.toTypedArray(),
            )
        }
    }
}

/** A single pattern match result. */
@Serializable
data class PatternMatch(
    val pattern: String,
    val position: Int,
)
