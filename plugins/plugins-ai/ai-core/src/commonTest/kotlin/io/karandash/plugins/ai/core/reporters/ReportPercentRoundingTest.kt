package io.karandash.plugins.ai.core.reporters

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.architecture.MutationTestResult
import io.karandash.plugins.ai.core.architecture.RuleMutationTester
import io.karandash.plugins.ai.statistics.RunRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Report percentages are rounded: a ratio such as 0.58 is 57.99999 after `* 100` and must not print as 57. */
class ReportPercentRoundingTest {
    private fun assertContains(
        expected: String,
        text: String,
    ) = assertTrue(expected in text, "expected '$expected' in:\n$text")

    @Test
    fun `manager digest pass rate is rounded`() =
        runTest {
            val findings = List(4) { Finding(Severity.ERROR, "e", "r") } + Finding(Severity.INFO, "i", "r")

            val summary = SmartDigestGenerator(aiService = null).generate(findings, DigestAudience.MANAGER).summary

            assertContains("Pass rate: 20%", summary)
        }

    @Test
    fun `flaky rule transition rate is rounded`() {
        // 51 runs, 29 flips in the first 30 runs: transition rate 29 of 50 = 58%
        val records = (0..50).map { i ->
            val fires = if (i < 30) i % 2 == 1 else true
            RunRecord(i.toLong(), null, 0, 0, 0, 0, mapOf("r" to if (fires) 1 else 0))
        }

        val issue = FlakyRuleDetector.detect(records).issues.single()

        assertContains("transition rate: 58%", issue.description)
    }

    @Test
    fun `policy drift rates and delta are rounded`() {
        val drift = RuleDrift("r", RuleDriftType.REGRESSION, baselineRate = 0.29, currentRate = 0.58, deltaPercent = (0.58 - 0.29) * 100)

        val text = PolicyDriftDetector.formatReport(PolicyDriftReport(listOf(drift), recentRuns = 5, baselineRuns = 5))

        assertContains("r: ↑29% (29% → 58%)", text)
    }

    @Test
    fun `team scorecard fire rate is rounded`() {
        val scorecard = TeamScorecard(ruleScores = mapOf("r" to RuleScore("r", score = 71, fireRate = 0.29, totalFindings = 3)))

        assertContains("| r | 71/100 | 29% | 3 |", TeamScorecardGenerator.formatReport(scorecard))
    }

    @Test
    fun `prediction probability is rounded`() {
        val text = AiPredictionReporter.formatReport(listOf(RulePrediction("r", 0.57, PredictionConfidence.HIGH)))

        assertContains("| r | 57% | HIGH |", text)
    }

    @Test
    fun `mutation score is rounded`() {
        val result = MutationTestResult(emptyList(), totalMutations = 100, caughtCount = 29, escapedCount = 71, mutationScore = 0.29)

        assertContains("Mutation score: 29% (29/100 caught)", RuleMutationTester.formatReport(result))
    }
}
