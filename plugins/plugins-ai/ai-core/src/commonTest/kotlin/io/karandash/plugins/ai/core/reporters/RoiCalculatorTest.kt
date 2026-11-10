package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoiCalculatorTest {
    private fun record(
        errorCount: Int = 0,
        warningCount: Int = 0,
        infoCount: Int = 0,
    ) = RunRecord(
        timestamp = 0L,
        mrId = null,
        totalFindings = errorCount + warningCount + infoCount,
        errorCount = errorCount,
        warningCount = warningCount,
        infoCount = infoCount,
        findingsByRule = emptyMap(),
    )

    @Test
    fun `empty records returns zero result`() {
        val result = RoiCalculator.calculate(emptyList())
        assertEquals(0, result.totalFindings)
        assertEquals(0, result.totalRuns)
        assertEquals(0.0, result.minutesSaved)
        assertEquals(0.0, result.hoursSaved)
        assertEquals(0.0, result.costSavedUsd)
    }

    @Test
    fun `error findings count more time saved than warnings`() {
        val errorsOnly = RoiCalculator.calculate(listOf(record(errorCount = 1)))
        val warningsOnly = RoiCalculator.calculate(listOf(record(warningCount = 1)))
        assertTrue(
            errorsOnly.minutesSaved > warningsOnly.minutesSaved,
            "Error should save more time than warning: " +
                "${errorsOnly.minutesSaved} vs ${warningsOnly.minutesSaved}",
        )
    }

    @Test
    fun `warning findings count more time saved than info`() {
        val warningsOnly = RoiCalculator.calculate(listOf(record(warningCount = 1)))
        val infosOnly = RoiCalculator.calculate(listOf(record(infoCount = 1)))
        assertTrue(
            warningsOnly.minutesSaved > infosOnly.minutesSaved,
            "Warning should save more time than info: " +
                "${warningsOnly.minutesSaved} vs ${infosOnly.minutesSaved}",
        )
    }

    @Test
    fun `custom hourly rate applied to cost calculation`() {
        val result = RoiCalculator.calculate(listOf(record(errorCount = 4)), hourlyRateUsd = 100.0)
        // 4 errors * 15 min = 60 min = 1 hour * $100 = $100
        assertEquals(100.0, result.costSavedUsd)
        assertEquals(100.0, result.hourlyRateUsd)
    }

    @Test
    fun `multiple runs accumulated correctly`() {
        val records = listOf(
            record(errorCount = 1),
            record(warningCount = 2),
            record(infoCount = 3),
        )
        val result = RoiCalculator.calculate(records)
        assertEquals(6, result.totalFindings)
        assertEquals(3, result.totalRuns)
        // 1*15 + 2*5 + 3*2 = 15 + 10 + 6 = 31 minutes
        assertEquals(31.0, result.minutesSaved)
    }

    @Test
    fun `hours saved derived from minutes saved`() {
        val result = RoiCalculator.calculate(listOf(record(errorCount = 4)))
        // 4 * 15 = 60 min = 1 hour
        assertEquals(1.0, result.hoursSaved)
    }

    @Test
    fun `formatReport generates readable output`() {
        val result = RoiResult(
            totalFindings = 50,
            totalRuns = 10,
            minutesSaved = 300.0,
            hoursSaved = 5.0,
            costSavedUsd = 375.0,
            hourlyRateUsd = 75.0,
        )
        val report = RoiCalculator.formatReport(result)
        assertTrue(report.contains("ROI Report"))
        assertTrue(report.contains("10"))
        assertTrue(report.contains("50"))
        assertTrue(report.contains("5.0 hours"))
        assertTrue(report.contains("\$375"))
        assertTrue(report.contains("\$75/hr"))
    }

    @Test
    fun `default hourly rate is 75`() {
        val result = RoiCalculator.calculate(listOf(record(errorCount = 4)))
        assertEquals(75.0, result.hourlyRateUsd)
    }
}
