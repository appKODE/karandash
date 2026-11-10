package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyDriftDetectorTest {
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
    fun emptyRecentReturnsEmptyReport() {
        val baseline = listOf(record(mapOf("rule-a" to 1)))
        val report = PolicyDriftDetector.detect(emptyList(), baseline)
        assertTrue(report.drifts.isEmpty())
        assertEquals(0, report.recentRuns)
    }

    @Test
    fun emptyBaselineReturnsEmptyReport() {
        val recent = listOf(record(mapOf("rule-a" to 1)))
        val report = PolicyDriftDetector.detect(recent, emptyList())
        assertTrue(report.drifts.isEmpty())
        assertEquals(0, report.baselineRuns)
    }

    @Test
    fun regressionDetectedWhenFireRateIncreasesAboveThreshold() {
        // baseline: rule fires 0% but is known (present with count 0); recent: rule fires 100%
        val baseline = List(5) { record(mapOf("rule-regressed" to 0)) }
        val recent = List(5) { record(mapOf("rule-regressed" to 1)) }
        val report = PolicyDriftDetector.detect(recent, baseline)
        val drift = report.drifts.find { it.ruleId == "rule-regressed" }
        assertTrue(drift != null, "Expected regression drift for rule-regressed")
        assertEquals(RuleDriftType.REGRESSION, drift.type)
        assertTrue(drift.deltaPercent > 10.0)
    }

    @Test
    fun improvementDetectedWhenFireRateDecreasesAboveThreshold() {
        // baseline: 100% fire rate; recent: rule known but fires 0%
        val baseline = List(5) { record(mapOf("rule-improved" to 1)) }
        val recent = List(5) { record(mapOf("rule-improved" to 0)) }
        val report = PolicyDriftDetector.detect(recent, baseline)
        val drift = report.drifts.find { it.ruleId == "rule-improved" }
        assertTrue(drift != null, "Expected improvement drift for rule-improved")
        assertEquals(RuleDriftType.IMPROVEMENT, drift.type)
        assertTrue(drift.deltaPercent < -10.0)
    }

    @Test
    fun newRuleDetected() {
        val baseline = List(3) { record(mapOf("rule-old" to 1)) }
        val recent = List(3) { record(mapOf("rule-old" to 1, "rule-new" to 1)) }
        val report = PolicyDriftDetector.detect(recent, baseline)
        val drift = report.drifts.find { it.ruleId == "rule-new" }
        assertTrue(drift != null, "Expected new-rule drift for rule-new")
        assertEquals(RuleDriftType.NEW_RULE, drift.type)
        assertEquals(0.0, drift.baselineRate)
    }

    @Test
    fun missingRuleDetected() {
        val baseline = List(3) { record(mapOf("rule-gone" to 1, "rule-other" to 1)) }
        val recent = List(3) { record(mapOf("rule-other" to 1)) }
        val report = PolicyDriftDetector.detect(recent, baseline)
        val drift = report.drifts.find { it.ruleId == "rule-gone" }
        assertTrue(drift != null, "Expected missing-rule drift for rule-gone")
        assertEquals(RuleDriftType.MISSING_RULE, drift.type)
        assertEquals(0.0, drift.currentRate)
    }

    @Test
    fun noDriftWhenRatesStable() {
        // Same fire rate in both periods - delta is 0, well within threshold
        val baseline = List(5) { record(mapOf("rule-stable" to 1)) }
        val recent = List(5) { record(mapOf("rule-stable" to 2)) }
        val report = PolicyDriftDetector.detect(recent, baseline)
        assertFalse(report.drifts.any { it.ruleId == "rule-stable" && it.type == RuleDriftType.REGRESSION })
        assertFalse(report.drifts.any { it.ruleId == "rule-stable" && it.type == RuleDriftType.IMPROVEMENT })
    }

    @Test
    fun hasRegressionFlagSetWhenRegressionPresent() {
        val baseline = List(5) { record(mapOf("rule-x" to 0)) }
        val recent = List(5) { record(mapOf("rule-x" to 1)) }
        val report = PolicyDriftDetector.detect(recent, baseline)
        assertTrue(report.hasRegression)
    }

    @Test
    fun hasRegressionFlagNotSetWhenNoRegression() {
        val baseline = List(5) { record(mapOf("rule-x" to 1)) }
        val recent = List(5) { record(mapOf("rule-x" to 1)) }
        val report = PolicyDriftDetector.detect(recent, baseline)
        assertFalse(report.hasRegression)
    }

    @Test
    fun driftsSortedByAbsoluteDeltaDescending() {
        // rule-big: 0% → 100% (+100), rule-small: 50% → 80% (+30)
        val baseline = (0 until 10).map { i ->
            val small = if (i < 5) mapOf("rule-small" to 1) else emptyMap()
            record(small)
        }
        val recent = (0 until 10).map { i ->
            val big = mapOf("rule-big" to 1)
            val small = if (i < 8) mapOf("rule-small" to 1) else emptyMap()
            record(big + small)
        }
        val report = PolicyDriftDetector.detect(recent, baseline)
        val absDeltas = report.drifts.map { kotlin.math.abs(it.deltaPercent) }
        assertEquals(absDeltas.sortedDescending(), absDeltas)
    }

    @Test
    fun formatReportShowsNoDriftMessage() {
        val report = PolicyDriftReport(drifts = emptyList(), recentRuns = 5, baselineRuns = 5)
        val text = PolicyDriftDetector.formatReport(report)
        assertTrue(text.contains("No significant drift detected."))
    }

    @Test
    fun formatReportShowsWarningForRegression() {
        val drift = RuleDrift("r", RuleDriftType.REGRESSION, 0.0, 0.5, 50.0)
        val report = PolicyDriftReport(
            drifts = listOf(drift),
            recentRuns = 5,
            baselineRuns = 5,
            hasRegression = true,
        )
        val text = PolicyDriftDetector.formatReport(report)
        assertTrue(text.contains("WARNING"))
        assertTrue(text.contains("Regressions detected"))
    }

    @Test
    fun formatReportContainsRunCounts() {
        val report = PolicyDriftReport(drifts = emptyList(), recentRuns = 7, baselineRuns = 12)
        val text = PolicyDriftDetector.formatReport(report)
        assertTrue(text.contains("7"))
        assertTrue(text.contains("12"))
    }
}
