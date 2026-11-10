package io.karandash.plugins.ai.core.research

import io.karandash.models.FileChange
import kotlinx.serialization.Serializable

/**
 * PageRank file importance: ranks files by their position in the import/dependency graph.
 * Files with high PageRank are architectural pillars - changes to them deserve extra scrutiny.
 *
 * Uses a simplified PageRank computed from import relationships extracted from diff content.
 */
object PageRankCalculator {
    private const val DAMPING_FACTOR = 0.85
    private const val ITERATIONS = 20
    private const val CONVERGENCE_THRESHOLD = 0.0001
    private const val PILLAR_THRESHOLD = 0.8

    private val IMPORT_REGEX = Regex("""import\s+[\w.]+\.(\w+)""")

    /**
     * Calculates PageRank scores for each changed file based on import relationships.
     *
     * @param changes The list of file changes to analyse.
     * @return List of [FileImportance] sorted descending by PageRank score.
     */
    fun calculate(changes: List<FileChange>): List<FileImportance> {
        val graph = buildImportGraph(changes)
        if (graph.isEmpty()) return emptyList()

        val nodes = (graph.keys + graph.values.flatten().toSet()).toSet()
        val n = nodes.size
        if (n == 0) return emptyList()

        var scores = nodes.associateWith { 1.0 / n }

        repeat(ITERATIONS) {
            val newScores = nodes.associateWith { node ->
                val inbound = graph.entries.filter { node in it.value }.map { it.key }
                (1.0 - DAMPING_FACTOR) / n +
                    DAMPING_FACTOR * inbound.sumOf { src ->
                        val outDegree = graph[src]?.size?.coerceAtLeast(1) ?: 1
                        (scores[src] ?: 0.0) / outDegree
                    }
            }
            val maxDelta = nodes.maxOfOrNull { kotlin.math.abs((newScores[it] ?: 0.0) - (scores[it] ?: 0.0)) } ?: 0.0
            scores = newScores
            if (maxDelta < CONVERGENCE_THRESHOLD) return@repeat
        }

        val maxScore = scores.values.maxOrNull() ?: 1.0
        return changes
            .map { change ->
                val score = scores[change.path] ?: 0.0
                val normalizedRank = if (maxScore > 0) score / maxScore else 0.0
                FileImportance(
                    path = change.path,
                    pageRank = score,
                    normalizedRank = normalizedRank,
                    isArchitecturalPillar = normalizedRank > PILLAR_THRESHOLD,
                )
            }.sortedByDescending { it.pageRank }
    }

    /**
     * Builds a directed import graph from the diff content of each file change.
     * An edge from A → B means A imports something from B.
     *
     * @param changes The list of file changes to extract imports from.
     * @return Map from file path to the set of other paths it imports.
     */
    fun buildImportGraph(changes: List<FileChange>): Map<String, Set<String>> {
        val graph = mutableMapOf<String, MutableSet<String>>()
        val allPaths = changes.map { it.path }.toSet()
        for (change in changes) {
            val diff = change.diffContent ?: continue
            val imports = extractImports(diff)
            val resolved = imports.mapNotNull { imp -> allPaths.firstOrNull { it.contains(imp) } }.toSet()
            if (resolved.isNotEmpty()) {
                graph.getOrPut(change.path) { mutableSetOf() }.addAll(resolved)
            }
        }
        return graph
    }

    private fun extractImports(diff: String): List<String> = IMPORT_REGEX.findAll(diff).map { it.groupValues[1] }.toList()
}

/**
 * PageRank-based importance score for a single file.
 *
 * @param path The file path.
 * @param pageRank Raw PageRank score.
 * @param normalizedRank PageRank score normalized to [0.0, 1.0] relative to the highest-ranked file.
 * @param isArchitecturalPillar True when [normalizedRank] exceeds the pillar threshold (0.8).
 */
@Serializable
data class FileImportance(
    val path: String,
    val pageRank: Double,
    val normalizedRank: Double,
    val isArchitecturalPillar: Boolean,
)
