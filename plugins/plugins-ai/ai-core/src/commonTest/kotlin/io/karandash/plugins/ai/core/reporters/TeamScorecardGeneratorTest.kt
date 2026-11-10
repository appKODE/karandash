package io.karandash.plugins.ai.core.reporters

import io.karandash.plugins.ai.statistics.RunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TeamScorecardGeneratorTest {
    private fun record(
        vararg ruleFindings: Pair<String, Int>,
        totalFindings: Int = ruleFindings.sumOf { it.second },
    ) = RunRecord(
        timestamp = 1_000L,
        mrId = null,
        totalFindings = totalFindings,
        errorCount = 0,
        warningCount = totalFindings,
        infoCount = 0,
        findingsByRule = mapOf(*ruleFindings),
    )

    @Test
    fun `empty records returns default scorecard`() {
        val scorecard = TeamScorecardGenerator.generate(emptyList())
        assertEquals(100, scorecard.compositeScore)
        assertTrue(scorecard.ruleScores.isEmpty())
        assertEquals(0, scorecard.totalRuns)
        assertEquals(0, scorecard.totalFindings)
    }

    @Test
    fun `perfect score when no findings`() {
        val records = listOf(
            record(totalFindings = 0),
            record(totalFindings = 0),
        )
        val scorecard = TeamScorecardGenerator.generate(records)
        assertEquals(100, scorecard.compositeScore)
        assertTrue(scorecard.ruleScores.isEmpty())
    }

    @Test
    fun `composite score calculated from rule score averages`() {
        // rule-a: 2 findings → score = 100 - 2*3 = 94
        // rule-b: 10 findings → score = 100 - 10*3 = 70
        // composite = (94 + 70) / 2 = 82
        val records = listOf(
            record("rule-a" to 1, "rule-b" to 5),
            record("rule-a" to 1, "rule-b" to 5),
        )
        val scorecard = TeamScorecardGenerator.generate(records)
        assertEquals(82, scorecard.compositeScore)
    }

    @Test
    fun `rule fire rate computed correctly`() {
        // rule-a fires in 2 of 3 runs
        val records = listOf(
            record("rule-a" to 1),
            record("rule-a" to 0),
            record("rule-a" to 2),
        )
        val scorecard = TeamScorecardGenerator.generate(records)
        val ruleScore = scorecard.ruleScores["rule-a"]
        assertNotNull(ruleScore)
        // fireCount = 2 (records where count > 0), totalRuns = 3
        assertEquals(2.0 / 3.0, ruleScore.fireRate, absoluteTolerance = 0.001)
    }

    @Test
    fun `score floored at zero for many findings`() {
        // 100 findings × 3 penalty = 300 > 100, so clamped to 0
        val records = listOf(record("rule-a" to 100))
        val scorecard = TeamScorecardGenerator.generate(records)
        assertEquals(0, scorecard.ruleScores["rule-a"]?.score)
    }

    @Test
    fun `totalFindings aggregated across all records`() {
        val records = listOf(
            record("rule-a" to 2, totalFindings = 2),
            record("rule-b" to 3, totalFindings = 3),
        )
        val scorecard = TeamScorecardGenerator.generate(records)
        assertEquals(5, scorecard.totalFindings)
    }

    @Test
    fun `formatReport produces markdown table`() {
        val records = listOf(record("title-format" to 2, "commit-messages" to 5))
        val scorecard = TeamScorecardGenerator.generate(records)
        val report = TeamScorecardGenerator.formatReport(scorecard)
        assertTrue(report.contains("## Team Hygiene Scorecard"))
        assertTrue(report.contains("| Rule |"))
        assertTrue(report.contains("title-format"))
        assertTrue(report.contains("commit-messages"))
    }

    @Test
    fun `formatReport rows sorted by score ascending`() {
        // rule-a: 1 finding → score 97; rule-b: 20 findings → score 40
        val records = listOf(record("rule-a" to 1, "rule-b" to 20))
        val scorecard = TeamScorecardGenerator.generate(records)
        val report = TeamScorecardGenerator.formatReport(scorecard)
        val ruleAIdx = report.indexOf("rule-a")
        val ruleBIdx = report.indexOf("rule-b")
        // rule-b has lower score, so appears first
        assertTrue(ruleBIdx < ruleAIdx, "Lower-scoring rule should appear first in table")
    }

    private fun assertEquals(
        expected: Double,
        actual: Double?,
        absoluteTolerance: Double
    ) {
        assertNotNull(actual)
        assertTrue(
            kotlin.math.abs(actual - expected) <= absoluteTolerance,
            "Expected $expected ± $absoluteTolerance but was $actual",
        )
    }
}
