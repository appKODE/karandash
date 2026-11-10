package io.karandash.plugins.ai.core

import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleSandboxTest {
    private val sandbox = RuleSandbox()

    private fun makeMr(
        id: String,
        title: String = "MR $id"
    ) = MergeRequest(
        id = id,
        iid = 1,
        title = title,
        description = null,
        sourceBranch = "feature",
        targetBranch = "main",
        author = User(username = "dev", name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
    )

    private fun finding(severity: Severity = Severity.WARNING) =
        Finding(
            severity = severity,
            message = "test finding",
            rule = "test-rule",
        )

    @Test
    fun emptySnapshotsReturnsZeroResults() {
        val result = sandbox.replay(emptyList(), "title-format") { emptyList() }
        assertEquals(0, result.snapshotsReplayed)
        assertTrue(result.findings.isEmpty())
        assertEquals(0, result.impactSummary.passCount)
        assertEquals(0, result.impactSummary.failCount)
        assertEquals(0.0, result.impactSummary.fireRate)
    }

    @Test
    fun allPassingSnapshotsProducesZeroFireRate() {
        val snapshots = listOf(makeMr("1"), makeMr("2"), makeMr("3"))
        val result = sandbox.replay(snapshots, "title-format") { emptyList() }
        assertEquals(3, result.snapshotsReplayed)
        assertEquals(0.0, result.impactSummary.fireRate)
        assertEquals(3, result.impactSummary.passCount)
        assertEquals(0, result.impactSummary.failCount)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun allFailingSnapshotsProducesFullFireRate() {
        val snapshots = listOf(makeMr("1"), makeMr("2"), makeMr("3"))
        val result = sandbox.replay(snapshots, "title-format") { listOf(finding()) }
        assertEquals(1.0, result.impactSummary.fireRate)
        assertEquals(0, result.impactSummary.passCount)
        assertEquals(3, result.impactSummary.failCount)
    }

    @Test
    fun mixedPassFailCalculatesCorrectFireRate() {
        val snapshots = listOf(makeMr("1"), makeMr("2"), makeMr("3"), makeMr("4"))
        val failing = setOf("1", "3")
        val result = sandbox.replay(snapshots, "title-format") { mr ->
            if (mr.id in failing) listOf(finding()) else emptyList()
        }
        assertEquals(0.5, result.impactSummary.fireRate)
        assertEquals(2, result.impactSummary.passCount)
        assertEquals(2, result.impactSummary.failCount)
    }

    @Test
    fun severityDistributionCountedCorrectly() {
        val snapshots = listOf(makeMr("1"), makeMr("2"), makeMr("3"))
        val result = sandbox.replay(snapshots, "title-format") { mr ->
            when (mr.id) {
                "1" -> listOf(finding(Severity.ERROR), finding(Severity.ERROR))
                "2" -> listOf(finding(Severity.WARNING))
                else -> emptyList()
            }
        }
        val dist = result.impactSummary.severityDistribution
        assertEquals(2, dist[Severity.ERROR])
        assertEquals(1, dist[Severity.WARNING])
        assertEquals(null, dist[Severity.INFO])
    }

    @Test
    fun affectedMrCountTracksUniqueMrs() {
        val snapshots = listOf(makeMr("1"), makeMr("2"), makeMr("3"))
        val result = sandbox.replay(snapshots, "title-format") { mr ->
            if (mr.id == "2") listOf(finding(), finding()) else emptyList()
        }
        assertEquals(1, result.impactSummary.affectedMrCount)
    }

    @Test
    fun validatorExceptionTreatedAsPass() {
        val snapshots = listOf(makeMr("1"), makeMr("2"))
        val result = sandbox.replay(snapshots, "title-format") { mr ->
            if (mr.id == "1") error("unexpected error") else emptyList()
        }
        assertEquals(2, result.impactSummary.passCount)
        assertEquals(0, result.impactSummary.failCount)
        assertEquals(0.0, result.impactSummary.fireRate)
    }

    @Test
    fun recommendationGeneratedForHighFireRate() {
        val snapshots = (1..15).map { makeMr("$it") }
        val result = sandbox.replay(snapshots, "title-format") { listOf(finding()) }
        assertTrue(result.impactSummary.recommendation.contains("CAUTION"))
        assertTrue(result.impactSummary.recommendation.contains("100%"))
    }

    @Test
    fun recommendationGeneratedForLowFireRate() {
        // 1 of 25 = 4% fire rate, well below the 5% LOW_FIRE_RATE threshold
        val snapshots = (1..25).map { makeMr("$it") }
        val result = sandbox.replay(snapshots, "title-format") { mr ->
            if (mr.id == "1") listOf(finding()) else emptyList()
        }
        assertTrue(result.impactSummary.recommendation.contains("rarely fires"))
    }

    @Test
    fun recommendationForInsufficientData() {
        val snapshots = listOf(makeMr("1"), makeMr("2"))
        val result = sandbox.replay(snapshots, "title-format") { listOf(finding()) }
        assertTrue(result.impactSummary.recommendation.contains("Insufficient data"))
    }

    @Test
    fun findingsAnnotatedWithMrIdAndTitle() {
        val mr = makeMr("42", "Fix the thing")
        val result = sandbox.replay(listOf(mr), "rule") { listOf(finding()) }
        val rf = result.findings.single()
        assertEquals("42", rf.mrId)
        assertEquals("Fix the thing", rf.mrTitle)
    }

    @Test
    fun `recommendation fire rate is rounded not truncated`() {
        // 58 of 100: 0.58 * 100 is 57.99999999999999 in IEEE 754
        val snapshots = (1..100).map { makeMr("$it") }
        val result = sandbox.replay(snapshots, "test-rule") { mr -> if (mr.id.toInt() <= 58) listOf(finding()) else emptyList() }
        assertEquals("Fire rate of 58% is within normal range", result.impactSummary.recommendation)
    }
}
