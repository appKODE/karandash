package io.karandash.plugins.ai.core.metrics

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HotspotAnalyzerTest {
    private fun makeMr(changes: List<FileChange> = emptyList()) =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = User("1", "dev", "Dev"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = changes,
        )

    private fun makeChange(
        path: String,
        additions: Int = 10,
        deletions: Int = 5
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = additions,
        deletions = deletions,
        diffContent = null,
    )

    @Test
    fun `empty MR returns empty list`() {
        val entries = HotspotAnalyzer.analyze(makeMr())
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `single file gets scored`() {
        val entries = HotspotAnalyzer.analyze(
            makeMr(listOf(makeChange("src/A.kt", additions = 50, deletions = 20))),
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].hotspotScore >= 0.0)
    }

    @Test
    fun `high churn and high complexity produces HIGH level`() {
        val change = makeChange("src/Core.kt", additions = 200, deletions = 200)
        val historicalChurn = mapOf("src/Core.kt" to 100)
        val entries = HotspotAnalyzer.analyze(makeMr(listOf(change)), historicalChurn)
        assertEquals(HotspotLevel.HIGH, entries[0].level)
    }

    @Test
    fun `low churn and low complexity produces LOW level`() {
        val change = makeChange("src/Tiny.kt", additions = 2, deletions = 1)
        val historicalChurn = mapOf("src/Tiny.kt" to 1, "src/Other.kt" to 100)
        val entries = HotspotAnalyzer.analyze(makeMr(listOf(change)), historicalChurn)
        assertEquals(HotspotLevel.LOW, entries[0].level)
    }

    @Test
    fun `results sorted by score descending`() {
        val changes = listOf(
            makeChange("src/Small.kt", additions = 5, deletions = 0),
            makeChange("src/Large.kt", additions = 150, deletions = 100),
            makeChange("src/Medium.kt", additions = 40, deletions = 20),
        )
        val entries = HotspotAnalyzer.analyze(makeMr(changes))
        for (i in 0 until entries.size - 1) {
            assertTrue(
                entries[i].hotspotScore >= entries[i + 1].hotspotScore,
                "Entries must be sorted descending: ${entries[i].hotspotScore} >= ${entries[i + 1].hotspotScore}",
            )
        }
    }

    @Test
    fun `historical churn data used when provided`() {
        val changes = listOf(
            makeChange("src/A.kt", additions = 10, deletions = 0),
            makeChange("src/B.kt", additions = 10, deletions = 0),
        )
        val withChurn = mapOf("src/A.kt" to 50, "src/B.kt" to 1)
        val entries = HotspotAnalyzer.analyze(makeMr(changes), withChurn)
        val entryA = entries.first { it.path == "src/A.kt" }
        val entryB = entries.first { it.path == "src/B.kt" }
        assertTrue(
            entryA.churn > entryB.churn,
            "File with higher historical churn should have higher churn score",
        )
    }

    @Test
    fun `scores bounded between 0 and 1`() {
        val changes = listOf(
            makeChange("src/Huge.kt", additions = 1000, deletions = 1000),
            makeChange("src/Zero.kt", additions = 0, deletions = 0),
        )
        val historicalChurn = mapOf("src/Huge.kt" to 9999, "src/Zero.kt" to 0)
        val entries = HotspotAnalyzer.analyze(makeMr(changes), historicalChurn)
        for (entry in entries) {
            assertTrue(entry.hotspotScore in 0.0..1.0, "hotspotScore out of bounds for ${entry.path}: ${entry.hotspotScore}")
            assertTrue(entry.complexity in 0.0..1.0, "complexity out of bounds for ${entry.path}: ${entry.complexity}")
            assertTrue(entry.churn in 0.0..1.0, "churn out of bounds for ${entry.path}: ${entry.churn}")
        }
    }

    @Test
    fun `without historical churn falls back to estimated churn from change size`() {
        val smallChange = makeChange("src/Small.kt", additions = 5, deletions = 0)
        val largeChange = makeChange("src/Large.kt", additions = 300, deletions = 100)
        val entries = HotspotAnalyzer.analyze(makeMr(listOf(smallChange, largeChange)))
        val small = entries.first { it.path == "src/Small.kt" }
        val large = entries.first { it.path == "src/Large.kt" }
        assertTrue(
            large.churn > small.churn,
            "Larger change should produce higher estimated churn: ${large.churn} vs ${small.churn}",
        )
    }

    @Test
    fun `file not in historical churn gets zero churn when map is non-empty`() {
        val change = makeChange("src/New.kt", additions = 10, deletions = 0)
        val historicalChurn = mapOf("src/Other.kt" to 50)
        val entries = HotspotAnalyzer.analyze(makeMr(listOf(change)), historicalChurn)
        assertEquals(0.0, entries[0].churn, "File absent from churn map should have churn=0.0")
    }
}
