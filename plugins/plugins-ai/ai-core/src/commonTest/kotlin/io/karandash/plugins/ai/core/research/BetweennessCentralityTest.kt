package io.karandash.plugins.ai.core.research

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BetweennessCentralityTest {
    @Test
    fun `empty graph returns empty list`() {
        val result = BetweennessCentrality.calculate(emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single node has zero centrality`() {
        val result = BetweennessCentrality.calculate(mapOf("A" to emptySet()))
        assertEquals(1, result.size)
        assertEquals(0.0, result[0].centrality)
        assertFalse(result[0].isBottleneck)
    }

    @Test
    fun `bridge node has highest centrality`() {
        // A -> B -> C: B is the only path between A and C
        val graph = mapOf(
            "A" to setOf("B"),
            "B" to setOf("C"),
            "C" to emptySet(),
        )
        val result = BetweennessCentrality.calculate(graph)
        val bridge = result.first { it.path == "B" }
        val endpointA = result.first { it.path == "A" }
        val endpointC = result.first { it.path == "C" }
        assertTrue(
            bridge.centrality >= endpointA.centrality,
            "Bridge B should have >= centrality than A: ${bridge.centrality} vs ${endpointA.centrality}",
        )
        assertTrue(
            bridge.centrality >= endpointC.centrality,
            "Bridge B should have >= centrality than C: ${bridge.centrality} vs ${endpointC.centrality}",
        )
    }

    @Test
    fun `bottleneck detection works`() {
        // Linear chain: A -> B -> C -> D. B and C should be bottlenecks.
        val graph = mapOf(
            "A" to setOf("B"),
            "B" to setOf("C"),
            "C" to setOf("D"),
            "D" to emptySet(),
        )
        val result = BetweennessCentrality.calculate(graph)
        val bottlenecks = result.filter { it.isBottleneck }
        assertTrue(bottlenecks.isNotEmpty(), "Linear chain should have at least one bottleneck")
    }

    @Test
    fun `results sorted descending by centrality`() {
        val graph = mapOf(
            "A" to setOf("B"),
            "B" to setOf("C"),
            "C" to emptySet(),
        )
        val result = BetweennessCentrality.calculate(graph)
        for (i in 0 until result.size - 1) {
            assertTrue(
                result[i].centrality >= result[i + 1].centrality,
                "Not sorted: index $i (${result[i].centrality}) < index ${i + 1} (${result[i + 1].centrality})",
            )
        }
    }

    @Test
    fun `two disconnected nodes both have zero centrality`() {
        val graph = mapOf(
            "A" to emptySet<String>(),
            "B" to emptySet(),
        )
        val result = BetweennessCentrality.calculate(graph)
        result.forEach { score ->
            assertEquals(0.0, score.centrality, "${score.path} should have zero centrality in disconnected graph")
        }
    }

    @Test
    fun `star topology hub has higher centrality than spokes`() {
        // Hub -> A, Hub -> B, Hub -> C
        val graph = mapOf(
            "Hub" to setOf("A", "B", "C"),
            "A" to emptySet(),
            "B" to emptySet(),
            "C" to emptySet(),
        )
        val result = BetweennessCentrality.calculate(graph)
        val hubScore = result.first { it.path == "Hub" }.centrality
        val spokeScores = listOf("A", "B", "C").map { name -> result.first { it.path == name }.centrality }
        spokeScores.forEach { spoke ->
            assertTrue(
                hubScore >= spoke,
                "Hub centrality $hubScore should be >= spoke centrality $spoke",
            )
        }
    }

    @Test
    fun `all nodes included in result`() {
        val graph = mapOf(
            "X" to setOf("Y"),
            "Y" to setOf("Z"),
            "Z" to emptySet(),
        )
        val result = BetweennessCentrality.calculate(graph)
        val paths = result.map { it.path }.toSet()
        assertTrue(paths.contains("X"))
        assertTrue(paths.contains("Y"))
        assertTrue(paths.contains("Z"))
    }
}
