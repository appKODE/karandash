package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlakyRuleDetectorTest {
    private fun record(findingsByRule: Map<String, Int> = emptyMap()) =
        RunRecord(
            timestamp = 1_000_000L,
            mrId = null,
            totalFindings = findingsByRule.values.sum(),
            errorCount = 0,
            warningCount = 0,
            infoCount = 0,
            findingsByRule = findingsByRule,
        )

    @Test
    fun tooFewRunsReturnsEmptyReport() {
        val records = List(4) { record(mapOf("rule-a" to 1)) }
        val report = FlakyRuleDetector.detect(records)
        assertTrue(report.issues.isEmpty())
        assertEquals(0, report.rulesAnalyzed)
        assertEquals(0, report.runsAnalyzed)
    }

    @Test
    fun exactlyFiveRunsIsAnalyzed() {
        val records = List(5) { record(mapOf("rule-a" to 1)) }
        val report = FlakyRuleDetector.detect(records)
        assertEquals(5, report.runsAnalyzed)
    }

    @Test
    fun flipFlopRuleDetected() {
        // Alternates fire/no-fire → high transition rate
        val records = (0 until 10).map { i ->
            if (i % 2 == 0) record(mapOf("rule-flip" to 1)) else record()
        }
        val report = FlakyRuleDetector.detect(records)
        val issue = report.issues.find { it.ruleId == "rule-flip" }
        assertTrue(issue != null, "Expected flip-flop issue for rule-flip")
        assertEquals(FlakyIssueType.FLIP_FLOP, issue.issueType)
    }

    @Test
    fun neverFireRuleDetectedAfterTenRuns() {
        // rule-silent never fires across 10 records; rule-other always fires
        val records = List(10) { record(mapOf("rule-other" to 1)) }
        // rule-silent appears in none of the records - it won't be in allRules at all
        // so we include it in one record with 0 so it's seen
        val recordsWithSilent = List(10) { record(mapOf("rule-other" to 1, "rule-silent" to 0)) }
        val report = FlakyRuleDetector.detect(recordsWithSilent)
        val issue = report.issues.find { it.ruleId == "rule-silent" }
        assertTrue(issue != null, "Expected never-fires issue for rule-silent")
        assertEquals(FlakyIssueType.NEVER_FIRES, issue.issueType)
        assertEquals(0.0, issue.fireRate)
    }

    @Test
    fun neverFireNotDetectedWithFewerThanTenRuns() {
        // 5 records with a rule that never fires - should not flag NEVER_FIRES
        val records = List(5) { record(mapOf("rule-silent" to 0)) }
        val report = FlakyRuleDetector.detect(records)
        assertFalse(report.issues.any { it.issueType == FlakyIssueType.NEVER_FIRES })
    }

    @Test
    fun alwaysFireRuleDetected() {
        // rule-loud fires on all 10 runs → fire rate = 1.0 ≥ 0.95
        val records = List(10) { record(mapOf("rule-loud" to 3)) }
        val report = FlakyRuleDetector.detect(records)
        val issue = report.issues.find { it.ruleId == "rule-loud" }
        assertTrue(issue != null, "Expected always-fires issue for rule-loud")
        assertEquals(FlakyIssueType.ALWAYS_FIRES, issue.issueType)
        assertEquals(1.0, issue.fireRate)
    }

    @Test
    fun stableRuleNotFlagged() {
        // rule-stable fires on 6/10 runs - not flip-flop, not never, not always
        val records = (0 until 10).map { i ->
            if (i < 6) record(mapOf("rule-stable" to 1)) else record()
        }
        val report = FlakyRuleDetector.detect(records)
        assertFalse(report.issues.any { it.ruleId == "rule-stable" })
    }

    @Test
    fun issuesSortedByFireRateDescending() {
        // rule-loud always fires (1.0), rule-flip alternates (~0.5)
        val records = (0 until 10).map { i ->
            val loud = mapOf("rule-loud" to 2)
            val flip = if (i % 2 == 0) mapOf("rule-flip" to 1) else emptyMap()
            record(loud + flip)
        }
        val report = FlakyRuleDetector.detect(records)
        val rates = report.issues.map { it.fireRate }
        assertEquals(rates.sortedDescending(), rates)
    }

    @Test
    fun formatReportShowsNoIssuesMessage() {
        val report = FlakyRuleReport(issues = emptyList(), rulesAnalyzed = 3, runsAnalyzed = 5)
        val text = FlakyRuleDetector.formatReport(report)
        assertTrue(text.contains("No flaky rules detected."))
    }

    @Test
    fun formatReportListsIssueCount() {
        val issue = FlakyRuleIssue("r", FlakyIssueType.FLIP_FLOP, "desc", 0.5)
        val report = FlakyRuleReport(issues = listOf(issue), rulesAnalyzed = 1, runsAnalyzed = 10)
        val text = FlakyRuleDetector.formatReport(report)
        assertTrue(text.contains("Issues found: 1"))
        assertTrue(text.contains("FLIP_FLOP"))
        assertTrue(text.contains("r"))
    }
}
