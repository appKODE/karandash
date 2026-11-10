package io.karandash.plugins.ai.core.research

import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.config.CognitiveComplexityConfig
import kotlinx.serialization.Serializable

/**
 * Cognitive complexity delta: per-MR complexity change tracking.
 *
 * Based on SonarQube's Cognitive Complexity metric (2017).
 * Computes the delta: if the MR increases cognitive complexity beyond a threshold, flag it.
 *
 * Uses a simplified heuristic: counts nesting depth, control flow keywords, and boolean operators.
 * Weights are configurable via [CognitiveComplexityConfig].
 */
object CognitiveComplexityDelta {
    private val CONTROL_FLOW = setOf("if", "else", "for", "while", "when", "switch", "catch", "try")
    private val BOOLEAN_OPS = setOf("&&", "||", "and", "or")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Computes the cognitive complexity delta for each changed file using default weights.
     */
    fun compute(changes: List<FileChange>): List<ComplexityDelta> = compute(changes, CognitiveComplexityConfig())

    /**
     * Computes the cognitive complexity delta for each changed file.
     *
     * @param changes The list of file changes to analyse.
     * @param config Configuration for complexity weights.
     * @return List of [ComplexityDelta] for each change that has diff content.
     */
    fun compute(
        changes: List<FileChange>,
        config: CognitiveComplexityConfig
    ): List<ComplexityDelta> =
        changes.mapNotNull { change ->
            val diff = change.diffContent ?: return@mapNotNull null
            val addedLines = diff
                .lines()
                .filter { it.startsWith("+") && !it.startsWith("+++") }
                .map { it.removePrefix("+") }
            val removedLines = diff
                .lines()
                .filter { it.startsWith("-") && !it.startsWith("---") }
                .map { it.removePrefix("-") }

            val addedComplexity = computeComplexity(addedLines, config)
            val removedComplexity = computeComplexity(removedLines, config)
            val delta = addedComplexity - removedComplexity

            ComplexityDelta(
                path = change.path,
                addedComplexity = addedComplexity,
                removedComplexity = removedComplexity,
                delta = delta,
                increased = delta > 0,
            )
        }

    /**
     * Computes a simplified cognitive complexity score for a set of lines.
     *
     * Counts control flow keywords weighted by nesting depth, plus boolean operators.
     */
    internal fun computeComplexity(
        lines: List<String>,
        config: CognitiveComplexityConfig = CognitiveComplexityConfig(),
    ): Int {
        var complexity = 0
        var nestingDepth = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.endsWith("{")) nestingDepth++
            if (trimmed.startsWith("}")) nestingDepth = (nestingDepth - 1).coerceAtLeast(0)

            val tokens = trimmed.split(WHITESPACE_REGEX)
            for (token in tokens) {
                if (token in CONTROL_FLOW) {
                    complexity += config.controlFlowWeight + nestingDepth * config.nestingWeight
                }
                if (token in BOOLEAN_OPS) {
                    complexity += config.booleanOpWeight
                }
            }
        }

        return complexity
    }

    /**
     * Returns the total cognitive complexity delta summed across all [deltas].
     *
     * @param deltas The per-file deltas to aggregate.
     * @return Sum of all [ComplexityDelta.delta] values.
     */
    fun totalDelta(deltas: List<ComplexityDelta>): Int = deltas.sumOf { it.delta }
}

/**
 * Cognitive complexity delta for a single file.
 *
 * @param path The file path.
 * @param addedComplexity Complexity score for added lines.
 * @param removedComplexity Complexity score for removed lines.
 * @param delta Net change ([addedComplexity] minus [removedComplexity]); positive means increased complexity.
 * @param increased True when [delta] is positive.
 */
@Serializable
data class ComplexityDelta(
    val path: String,
    val addedComplexity: Int,
    val removedComplexity: Int,
    val delta: Int,
    val increased: Boolean,
)
