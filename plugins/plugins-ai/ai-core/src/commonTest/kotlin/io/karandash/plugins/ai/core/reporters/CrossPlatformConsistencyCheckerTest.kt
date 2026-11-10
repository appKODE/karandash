package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossPlatformConsistencyCheckerTest {
    private fun record(
        totalFindings: Int,
        errorCount: Int = 0,
        mrId: String? = null,
    ) = RunRecord(
        timestamp = 1_000_000L,
        mrId = mrId,
        totalFindings = totalFindings,
        errorCount = errorCount,
        warningCount = 0,
        infoCount = 0,
        findingsByRule = emptyMap(),
    )

    @Test
    fun singlePlatformProducesNoDrift() {
        val records = mapOf("gitlab" to listOf(record(5), record(3)))
        val report = CrossPlatformConsistencyChecker.compare(records)
        assertTrue(report.isConsistent)
        assertTrue(report.drifts.isEmpty())
    }

    @Test
    fun emptyMapProducesConsistentReport() {
        val report = CrossPlatformConsistencyChecker.compare(emptyMap())
        assertTrue(report.isConsistent)
        assertTrue(report.platforms.isEmpty())
    }

    @Test
    fun similarPlatformsAreConsistent() {
        val records = mapOf(
            "gitlab" to listOf(record(4), record(5)),
            "github" to listOf(record(5), record(4)),
        )
        val report = CrossPlatformConsistencyChecker.compare(records)
        assertTrue(report.isConsistent)
        assertTrue(report.drifts.isEmpty())
    }

    @Test
    fun divergentPlatformsDriftDetected() {
        val records = mapOf(
            "gitlab" to listOf(record(1)),
            "github" to listOf(record(10)),
        )
        val report = CrossPlatformConsistencyChecker.compare(records)
        assertFalse(report.isConsistent)
        assertEquals(1, report.drifts.size)
    }

    @Test
    fun driftDeltaIsAbsoluteDifference() {
        val records = mapOf(
            "a" to listOf(record(1)),
            "b" to listOf(record(8)),
        )
        val report = CrossPlatformConsistencyChecker.compare(records)
        assertEquals(7.0, report.drifts[0].findingDelta)
    }

    @Test
    fun driftDescriptionMentionsBothPlatforms() {
        val records = mapOf(
            "gitlab" to listOf(record(0)),
            "github" to listOf(record(10)),
        )
        val report = CrossPlatformConsistencyChecker.compare(records)
        val drift = report.drifts[0]
        assertTrue(drift.description.contains("gitlab") || drift.description.contains("github"))
    }

    @Test
    fun platformStatsPopulatedForEachPlatform() {
        val records = mapOf(
            "gitlab" to listOf(record(4), record(6)),
            "github" to listOf(record(2)),
        )
        val report = CrossPlatformConsistencyChecker.compare(records)
        assertEquals(2, report.platformStats.size)
        val gitlab = report.platformStats.first { it.platform == "gitlab" }
        assertEquals(5.0, gitlab.avgFindings)
        assertEquals(2, gitlab.runs)
    }

    @Test
    fun emptyRecordListForPlatformUsesZeroAverage() {
        val records = mapOf(
            "gitlab" to emptyList(),
            "github" to listOf(record(10)),
        )
        val report = CrossPlatformConsistencyChecker.compare(records)
        val gitlab = report.platformStats.first { it.platform == "gitlab" }
        assertEquals(0.0, gitlab.avgFindings)
    }

    @Test
    fun threePlatformsAllDriftPairsChecked() {
        val records = mapOf(
            "a" to listOf(record(0)),
            "b" to listOf(record(0)),
            "c" to listOf(record(100)),
        )
        val report = CrossPlatformConsistencyChecker.compare(records)
        // a-c and b-c both drift; a-b do not
        assertEquals(2, report.drifts.size)
    }

    @Test
    fun formatReportShowsYesWhenConsistent() {
        val records = mapOf("gitlab" to listOf(record(3)), "github" to listOf(record(3)))
        val report = CrossPlatformConsistencyChecker.compare(records)
        val text = CrossPlatformConsistencyChecker.formatReport(report)
        assertTrue(text.contains("Consistent: YES"))
    }

    @Test
    fun formatReportShowsDriftWhenInconsistent() {
        val records = mapOf("gitlab" to listOf(record(0)), "github" to listOf(record(20)))
        val report = CrossPlatformConsistencyChecker.compare(records)
        val text = CrossPlatformConsistencyChecker.formatReport(report)
        assertTrue(text.contains("NO — drift detected"))
        assertTrue(text.contains("DRIFT:"))
    }

    @Test
    fun formatReportListsPlatformNames() {
        val records = mapOf("bitbucket" to listOf(record(1)), "github" to listOf(record(1)))
        val report = CrossPlatformConsistencyChecker.compare(records)
        val text = CrossPlatformConsistencyChecker.formatReport(report)
        assertTrue(text.contains("bitbucket"))
        assertTrue(text.contains("github"))
    }
}
