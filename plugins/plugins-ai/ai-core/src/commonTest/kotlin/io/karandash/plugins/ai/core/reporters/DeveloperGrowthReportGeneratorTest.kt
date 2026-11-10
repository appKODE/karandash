package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeveloperGrowthReportGeneratorTest {
    private fun record(
        timestamp: Long,
        totalFindings: Int,
        ruleFindings: Map<String, Int> = emptyMap(),
    ) = RunRecord(
        timestamp = timestamp,
        mrId = null,
        totalFindings = totalFindings,
        errorCount = 0,
        warningCount = totalFindings,
        infoCount = 0,
        findingsByRule = ruleFindings,
    )

    @Test
    fun `empty records returns default data`() {
        val data = DeveloperGrowthReportGenerator.generate("alice", emptyList())
        assertEquals("alice", data.authorId)
        assertEquals(0, data.totalRuns)
        assertEquals(0.0, data.averageFindingsPerRun)
        assertEquals(GrowthTrend.STABLE, data.trend)
        assertTrue(data.persistentProblemRules.isEmpty())
    }

    @Test
    fun `improving trend when second half has fewer findings`() {
        val records = listOf(
            record(1L, 20),
            record(2L, 18),
            record(3L, 4),
            record(4L, 2),
        )
        val data = DeveloperGrowthReportGenerator.generate("bob", records)
        assertEquals(GrowthTrend.IMPROVING, data.trend)
        assertTrue(data.improvementPercent > 0.0)
    }

    @Test
    fun `declining trend when second half has more findings`() {
        val records = listOf(
            record(1L, 2),
            record(2L, 3),
            record(3L, 20),
            record(4L, 25),
        )
        val data = DeveloperGrowthReportGenerator.generate("carol", records)
        assertEquals(GrowthTrend.DECLINING, data.trend)
        assertTrue(data.improvementPercent < 0.0)
    }

    @Test
    fun `stable trend when halves have similar findings`() {
        val records = listOf(
            record(1L, 5),
            record(2L, 5),
            record(3L, 5),
            record(4L, 5),
        )
        val data = DeveloperGrowthReportGenerator.generate("dave", records)
        assertEquals(GrowthTrend.STABLE, data.trend)
        assertEquals(0.0, data.improvementPercent)
    }

    @Test
    fun `persistent rules identified when fire rate exceeds 50 percent`() {
        // rule-x fires in 3 of 4 runs (75%) → persistent
        // rule-y fires in 1 of 4 runs (25%) → not persistent
        val records = listOf(
            record(1L, 2, mapOf("rule-x" to 1, "rule-y" to 1)),
            record(2L, 1, mapOf("rule-x" to 1)),
            record(3L, 1, mapOf("rule-x" to 1)),
            record(4L, 0, emptyMap()),
        )
        val data = DeveloperGrowthReportGenerator.generate("eve", records)
        assertTrue("rule-x" in data.persistentProblemRules, "rule-x should be persistent")
        assertTrue("rule-y" !in data.persistentProblemRules, "rule-y should not be persistent")
    }

    @Test
    fun `single record produces stable trend`() {
        val data = DeveloperGrowthReportGenerator.generate("frank", listOf(record(1L, 3)))
        assertEquals(GrowthTrend.STABLE, data.trend)
        assertEquals(1, data.totalRuns)
    }

    @Test
    fun `records sorted by timestamp before splitting`() {
        // Records provided in reverse order; generator must sort before splitting
        val records = listOf(
            record(4L, 2),
            record(3L, 1),
            record(2L, 20),
            record(1L, 18),
        )
        val dataSorted = DeveloperGrowthReportGenerator.generate("grace", records)
        val dataForward = DeveloperGrowthReportGenerator.generate(
            "grace",
            listOf(record(1L, 18), record(2L, 20), record(3L, 1), record(4L, 2)),
        )
        assertEquals(dataSorted.trend, dataForward.trend)
        assertEquals(dataSorted.improvementPercent, dataForward.improvementPercent)
    }

    @Test
    fun `formatReport produces readable output`() {
        val data = DeveloperGrowthData(
            authorId = "heidi",
            totalRuns = 10,
            averageFindingsPerRun = 3.5,
            improvementPercent = 25.0,
            trend = GrowthTrend.IMPROVING,
            persistentProblemRules = listOf("title-format"),
        )
        val report = DeveloperGrowthReportGenerator.formatReport(data)
        assertTrue(report.contains("## Developer Growth Report: heidi"))
        assertTrue(report.contains("IMPROVING"))
        assertTrue(report.contains("title-format"))
        assertTrue(report.contains("3.5"))
    }

    @Test
    fun `formatReport omits persistent issues line when none`() {
        val data = DeveloperGrowthData(authorId = "ivan", persistentProblemRules = emptyList())
        val report = DeveloperGrowthReportGenerator.formatReport(data)
        assertTrue(!report.contains("Persistent issues"))
    }

    @Test
    fun `report change percent is rounded not truncated`() {
        // (100 - 71) / 100 * 100 is 28.999999999999996 in IEEE 754
        val data = DeveloperGrowthReportGenerator.generate("kim", listOf(record(1L, 100), record(2L, 71)))
        val report = DeveloperGrowthReportGenerator.formatReport(data)
        assertTrue("(29% change)" in report, report)
    }
}
