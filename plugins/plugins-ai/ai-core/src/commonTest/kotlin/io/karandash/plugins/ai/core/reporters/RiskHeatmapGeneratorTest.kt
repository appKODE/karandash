package io.karandash.plugins.ai.core.reporters

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskHeatmapGeneratorTest {
    private fun finding(
        path: String,
        severity: Severity,
    ) = Finding(
        severity = severity,
        message = "test",
        rule = "test-rule",
        location = Location.FileRef(path = path),
    )

    private fun findingNoLocation(severity: Severity = Severity.ERROR) =
        Finding(
            severity = severity,
            message = "test",
            rule = "test-rule",
            location = null,
        )

    private fun findingCommitRef(severity: Severity = Severity.ERROR) =
        Finding(
            severity = severity,
            message = "test",
            rule = "test-rule",
            location = Location.CommitRef(sha = "abc123"),
        )

    @Test
    fun `empty findings returns empty heatmap`() {
        val heatmap = RiskHeatmapGenerator.generate(emptyList())
        assertTrue(heatmap.files.isEmpty())
        assertTrue(heatmap.directories.isEmpty())
    }

    @Test
    fun `findings without FileRef location are ignored`() {
        val findings = listOf(findingNoLocation(), findingCommitRef())
        val heatmap = RiskHeatmapGenerator.generate(findings)
        assertTrue(heatmap.files.isEmpty())
    }

    @Test
    fun `file scores weighted by severity`() {
        // ERROR=3, WARNING=1, INFO=0
        val findings = listOf(
            finding("src/A.kt", Severity.ERROR),
            finding("src/A.kt", Severity.WARNING),
            finding("src/A.kt", Severity.INFO),
        )
        val heatmap = RiskHeatmapGenerator.generate(findings)
        val entry = heatmap.files.first { it.path == "src/A.kt" }
        assertEquals(4, entry.score) // 3 + 1 + 0
        assertEquals(3, entry.findingCount)
    }

    @Test
    fun `files sorted by score descending`() {
        val findings = listOf(
            finding("src/Low.kt", Severity.WARNING),
            finding("src/High.kt", Severity.ERROR),
            finding("src/High.kt", Severity.ERROR),
            finding("src/Mid.kt", Severity.ERROR),
        )
        val heatmap = RiskHeatmapGenerator.generate(findings)
        val scores = heatmap.files.map { it.score }
        for (i in 0 until scores.size - 1) {
            assertTrue(scores[i] >= scores[i + 1], "Files must be sorted descending: ${scores[i]} >= ${scores[i + 1]}")
        }
    }

    @Test
    fun `directory scores aggregated from file scores`() {
        val findings = listOf(
            finding("src/main/A.kt", Severity.ERROR),
            finding("src/main/B.kt", Severity.WARNING),
        )
        val heatmap = RiskHeatmapGenerator.generate(findings)
        val dirEntry = heatmap.directories.firstOrNull { it.path == "src/main" }
        assertTrue(dirEntry != null, "Expected directory entry for src/main")
        assertEquals(4, dirEntry.score) // 3 + 1
    }

    @Test
    fun `no directory entry for top-level files`() {
        // A file with no slash: substringBeforeLast('/') == itself → no dir added
        val findings = listOf(finding("README.md", Severity.ERROR))
        val heatmap = RiskHeatmapGenerator.generate(findings)
        assertTrue(heatmap.directories.isEmpty())
    }

    @Test
    fun `normalized score of highest file is 1 point 0`() {
        val findings = listOf(
            finding("src/Hot.kt", Severity.ERROR),
            finding("src/Hot.kt", Severity.ERROR),
            finding("src/Cool.kt", Severity.WARNING),
        )
        val heatmap = RiskHeatmapGenerator.generate(findings)
        val hot = heatmap.files.first { it.path == "src/Hot.kt" }
        assertEquals(1.0, hot.normalizedScore)
    }

    @Test
    fun `info findings contribute zero weight`() {
        val findings = listOf(finding("src/A.kt", Severity.INFO))
        val heatmap = RiskHeatmapGenerator.generate(findings)
        val entry = heatmap.files.first { it.path == "src/A.kt" }
        assertEquals(0, entry.score)
        assertEquals(1, entry.findingCount)
    }

    @Test
    fun `formatReport produces markdown`() {
        val findings = listOf(
            finding("src/Foo.kt", Severity.ERROR),
            finding("src/bar/Baz.kt", Severity.WARNING),
        )
        val heatmap = RiskHeatmapGenerator.generate(findings)
        val report = RiskHeatmapGenerator.formatReport(heatmap)
        assertTrue(report.contains("## Risk Heatmap"))
        assertTrue(report.contains("### Hottest Files"))
        assertTrue(report.contains("src/Foo.kt"))
    }

    @Test
    fun `formatReport includes directory section when dirs present`() {
        val findings = listOf(finding("src/main/A.kt", Severity.ERROR))
        val heatmap = RiskHeatmapGenerator.generate(findings)
        val report = RiskHeatmapGenerator.formatReport(heatmap)
        assertTrue(report.contains("### Hottest Directories"))
        assertTrue(report.contains("src/main"))
    }
}
