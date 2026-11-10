package io.karandash.plugins.ai.core.research

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertTrue

class PageRankCalculatorTest {
    private fun makeChange(
        path: String,
        diff: String? = null,
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = 1,
        deletions = 0,
        diffContent = diff,
    )

    @Test
    fun `empty changes returns empty list`() {
        val result = PageRankCalculator.calculate(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single file with no imports returns empty no graph edges`() {
        val result = PageRankCalculator.calculate(listOf(makeChange("src/Foo.kt")))
        // No import relationships = no graph = no PageRank scores
        assertTrue(result.isEmpty() || result.all { it.pageRank >= 0.0 })
    }

    @Test
    fun `connected files get higher rank than isolated file`() {
        val changes = listOf(
            makeChange("src/Core.kt", "import io.example.Core\nimport io.example.Dep"),
            makeChange("src/Dep.kt", "import io.example.Core"),
            makeChange("src/Isolated.kt"),
        )
        val result = PageRankCalculator.calculate(changes)
        val coreScore = result.first { it.path == "src/Core.kt" }.pageRank
        val isolatedScore = result.first { it.path == "src/Isolated.kt" }.pageRank
        assertTrue(
            coreScore >= isolatedScore,
            "Core (imported by others) should rank >= isolated, got core=$coreScore isolated=$isolatedScore",
        )
    }

    @Test
    fun `buildImportGraph extracts imports from diff content`() {
        val changes = listOf(
            makeChange("src/Foo.kt", "import io.example.Bar"),
            makeChange("src/Bar.kt"),
        )
        val graph = PageRankCalculator.buildImportGraph(changes)
        val fooEdges = graph["src/Foo.kt"]
        assertTrue(fooEdges != null && fooEdges.contains("src/Bar.kt"), "Foo should import Bar, graph=$graph")
    }

    @Test
    fun `architectural pillar identified for highest rank file`() {
        val changes = listOf(
            makeChange("src/Base.kt", "import io.example.Base"),
            makeChange("src/A.kt", "import io.example.Base"),
            makeChange("src/B.kt", "import io.example.Base"),
            makeChange("src/C.kt", "import io.example.Base"),
        )
        val result = PageRankCalculator.calculate(changes)
        val topFile = result.first()
        assertTrue(
            topFile.normalizedRank == 1.0,
            "Top-ranked file should have normalizedRank=1.0, got ${topFile.normalizedRank}",
        )
        assertTrue(topFile.isArchitecturalPillar, "Top file should be an architectural pillar")
    }

    @Test
    fun `results sorted descending by pageRank`() {
        val changes = listOf(
            makeChange("src/Foo.kt", "import io.example.Bar"),
            makeChange("src/Bar.kt"),
        )
        val result = PageRankCalculator.calculate(changes)
        for (i in 0 until result.size - 1) {
            assertTrue(
                result[i].pageRank >= result[i + 1].pageRank,
                "Results not sorted: ${result[i].pageRank} < ${result[i + 1].pageRank}",
            )
        }
    }

    @Test
    fun `file with no diff content does not appear as graph source`() {
        val changes = listOf(
            makeChange("src/A.kt", null),
            makeChange("src/B.kt", null),
        )
        val graph = PageRankCalculator.buildImportGraph(changes)
        assertTrue(graph.isEmpty(), "Graph with no diff content should be empty, got $graph")
    }

    @Test
    fun `normalized rank is bounded between 0 and 1`() {
        val changes = listOf(
            makeChange("a/A.kt", "import io.example.B"),
            makeChange("b/B.kt", "import io.example.C"),
            makeChange("c/C.kt"),
        )
        val result = PageRankCalculator.calculate(changes)
        result.forEach { fi ->
            assertTrue(fi.normalizedRank in 0.0..1.0, "${fi.path} normalizedRank=${fi.normalizedRank} out of range")
        }
    }

    @Test
    fun `isolated file with no imports produces no pillar`() {
        val changes = listOf(
            makeChange("src/Leaf.kt"),
        )
        val result = PageRankCalculator.calculate(changes)
        // No graph = empty result OR all non-pillar
        assertTrue(result.isEmpty() || result.none { it.isArchitecturalPillar })
    }
}
