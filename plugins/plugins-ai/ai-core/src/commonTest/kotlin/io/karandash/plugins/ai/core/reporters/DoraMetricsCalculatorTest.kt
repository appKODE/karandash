package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoraMetricsCalculatorTest {
    private fun record(
        errorCount: Int = 0,
        warningCount: Int = 0,
        infoCount: Int = 0,
        timestamp: Long = 0L,
    ) = RunRecord(
        timestamp = timestamp,
        mrId = null,
        totalFindings = errorCount + warningCount + infoCount,
        errorCount = errorCount,
        warningCount = warningCount,
        infoCount = infoCount,
        findingsByRule = emptyMap(),
    )

    @Test
    fun `empty records returns default zero metrics`() {
        val metrics = DoraMetricsCalculator.calculate(emptyList())
        assertEquals(0.0, metrics.changeFailureRate)
        assertEquals(0.0, metrics.deployFrequencyPerDay)
        assertEquals(0.0, metrics.averageFindingsPerRun)
        assertEquals(0, metrics.totalRuns)
        assertEquals(0, metrics.failedRuns)
    }

    @Test
    fun `change failure rate calculated correctly`() {
        val records = listOf(
            record(errorCount = 1),
            record(errorCount = 0),
            record(errorCount = 1),
            record(errorCount = 0),
        )
        val metrics = DoraMetricsCalculator.calculate(records)
        assertEquals(0.5, metrics.changeFailureRate)
    }

    @Test
    fun `all runs failed gives 100 percent failure rate`() {
        val records = listOf(record(errorCount = 2), record(errorCount = 1))
        val metrics = DoraMetricsCalculator.calculate(records)
        assertEquals(1.0, metrics.changeFailureRate)
    }

    @Test
    fun `no failures gives zero failure rate`() {
        val records = listOf(record(errorCount = 0), record(warningCount = 3))
        val metrics = DoraMetricsCalculator.calculate(records)
        assertEquals(0.0, metrics.changeFailureRate)
    }

    @Test
    fun `deploy frequency calculated from timestamps`() {
        // RunRecord.timestamp is epoch seconds: 10 runs over a 9-day span is ~1.11/day
        val secondsPerDay = 24L * 3600L
        val records = (0 until 10).map { i -> record(timestamp = i * secondsPerDay) }
        val metrics = DoraMetricsCalculator.calculate(records)
        assertEquals(10.0 / 9.0, metrics.deployFrequencyPerDay, 1e-9)
    }

    @Test
    fun `single record returns default frequency of 1`() {
        val metrics = DoraMetricsCalculator.calculate(listOf(record()))
        assertEquals(1.0, metrics.deployFrequencyPerDay)
    }

    @Test
    fun `formatReport generates readable output with window days`() {
        val metrics = DoraMetrics(
            changeFailureRate = 0.25,
            deployFrequencyPerDay = 2.5,
            averageFindingsPerRun = 3.0,
            totalRuns = 20,
            failedRuns = 5,
            windowDays = 30,
        )
        val report = DoraMetricsCalculator.formatReport(metrics)
        assertTrue(report.contains("DORA Metrics"))
        assertTrue(report.contains("30-day window"))
        assertTrue(report.contains("25%"))
        assertTrue(report.contains("2.5/day"))
        assertTrue(report.contains("20"))
        assertTrue(report.contains("5 failed"))
    }

    @Test
    fun `averageFindingsPerRun is mean of all records`() {
        val records = listOf(
            record(errorCount = 2),
            record(errorCount = 4),
        )
        val metrics = DoraMetricsCalculator.calculate(records)
        assertEquals(3.0, metrics.averageFindingsPerRun)
    }

    @Test
    fun `windowDays is passed through to result`() {
        val metrics = DoraMetricsCalculator.calculate(listOf(record()), windowDays = 7)
        assertEquals(7, metrics.windowDays)
    }
}
