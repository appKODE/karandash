package io.karandash.plugins.ai.core.research

import io.karandash.plugins.ai.core.config.BetweennessConfig
import kotlinx.serialization.Serializable

/**
 * Betweenness centrality: identifies "bridge" files that connect many code paths.
 * Files with high betweenness are bottlenecks - if broken, many paths disconnect.
 *
 * C_B(v) = sum of (shortest paths through v) / (total shortest paths between all pairs)
 *
 * The bottleneck threshold is configurable via [BetweennessConfig].
 */
object BetweennessCentrality {
    /**
     * Calculates betweenness centrality using default configuration.
     */
    fun calculate(graph: Map<String, Set<String>>): List<CentralityScore> = calculate(graph, BetweennessConfig())

    /**
     * Calculates betweenness centrality for all nodes in the graph.
     *
     * @param graph Directed adjacency map: node → set of outbound neighbours.
     * @param config Configuration for bottleneck threshold.
     * @return List of [CentralityScore] sorted descending by centrality.
     */
    fun calculate(
        graph: Map<String, Set<String>>,
        config: BetweennessConfig
    ): List<CentralityScore> {
        val nodes = (graph.keys + graph.values.flatten()).toSet().toList()
        if (nodes.size < 2) return nodes.map { CentralityScore(it, 0.0, false) }

        val scores = mutableMapOf<String, Double>()
        nodes.forEach { scores[it] = 0.0 }

        for (source in nodes) {
            val (dist, paths, predecessors) = bfs(source, graph, nodes)
            val dependency = mutableMapOf<String, Double>()
            nodes.forEach { dependency[it] = 0.0 }

            val sorted = nodes.sortedByDescending { dist[it] ?: Int.MAX_VALUE }
            for (w in sorted) {
                if (w == source) continue
                for (v in predecessors[w].orEmpty()) {
                    val pathsV = (paths[v] ?: 0).toDouble()
                    val pathsW = (paths[w] ?: 1).toDouble()
                    val share = if (pathsW > 0) (pathsV / pathsW) * (1.0 + (dependency[w] ?: 0.0)) else 0.0
                    dependency[v] = (dependency[v] ?: 0.0) + share
                }
                scores[w] = (scores[w] ?: 0.0) + (dependency[w] ?: 0.0)
            }
        }

        val n = nodes.size
        val normFactor = ((n - 1) * (n - 2)).toDouble().coerceAtLeast(1.0)
        val maxScore = scores.values.maxOrNull() ?: 1.0

        return nodes
            .map { node ->
                val raw = scores[node] ?: 0.0
                val normalized = raw / normFactor
                CentralityScore(
                    path = node,
                    centrality = normalized,
                    isBottleneck = maxScore > 0 && raw / maxScore > config.bottleneckThreshold.fraction,
                )
            }.sortedByDescending { it.centrality }
    }

    @Serializable
    private data class BfsResult(
        val dist: Map<String, Int>,
        val paths: Map<String, Int>,
        val predecessors: Map<String, List<String>>,
    )

    private fun bfs(
        source: String,
        graph: Map<String, Set<String>>,
        nodes: List<String>,
    ): BfsResult {
        val dist = mutableMapOf<String, Int>()
        val paths = mutableMapOf<String, Int>()
        val preds = mutableMapOf<String, MutableList<String>>()
        nodes.forEach {
            dist[it] = -1
            paths[it] = 0
            preds[it] = mutableListOf()
        }
        dist[source] = 0
        paths[source] = 1
        val queue = ArrayDeque<String>()
        queue.add(source)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (w in graph[v].orEmpty()) {
                if (dist[w] == -1) {
                    dist[w] = (dist[v] ?: 0) + 1
                    queue.add(w)
                }
                if (dist[w] == (dist[v] ?: 0) + 1) {
                    paths[w] = (paths[w] ?: 0) + (paths[v] ?: 0)
                    preds[w]?.add(v)
                }
            }
        }
        return BfsResult(dist, paths, preds)
    }
}

/**
 * Betweenness centrality score for a single node in the dependency graph.
 *
 * @param path The file path or node identifier.
 * @param centrality Normalised betweenness centrality score.
 * @param isBottleneck True when this node's raw score exceeds 50 % of the maximum observed score.
 */
@Serializable
data class CentralityScore(
    val path: String,
    val centrality: Double,
    val isBottleneck: Boolean,
)
