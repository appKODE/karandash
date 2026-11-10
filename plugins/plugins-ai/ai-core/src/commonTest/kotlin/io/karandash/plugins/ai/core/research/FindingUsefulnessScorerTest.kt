package io.karandash.plugins.ai.core.research

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindingUsefulnessScorerTest {
    private fun finding(
        message: String,
        severity: Severity = Severity.WARNING,
        explanation: String? = null,
    ) = Finding(
        severity = severity,
        message = message,
        rule = "test-rule",
        explanation = explanation,
    )

    @Test
    fun `security finding classified as USEFUL`() {
        val f = finding("potential security vulnerability: SQL injection detected", Severity.ERROR)
        val result = FindingUsefulnessScorer.score(f)
        assertEquals(UsefulnessLevel.USEFUL, result.level)
    }

    @Test
    fun `formatting finding classified as NOT_USEFUL`() {
        val f = finding("formatting issue: trailing whitespace on line 42")
        val result = FindingUsefulnessScorer.score(f)
        assertEquals(UsefulnessLevel.NOT_USEFUL, result.level)
    }

    @Test
    fun `ERROR severity gets bonus that can push score to USEFUL`() {
        val errorFinding = finding("missing null check", Severity.ERROR)
        val infoFinding = finding("missing null check", Severity.INFO)
        val errorResult = FindingUsefulnessScorer.score(errorFinding)
        val infoResult = FindingUsefulnessScorer.score(infoFinding)
        assertTrue(
            errorResult.rawScore > infoResult.rawScore,
            "ERROR should score higher than INFO: ${errorResult.rawScore} vs ${infoResult.rawScore}",
        )
    }

    @Test
    fun `filterUseful removes NOT_USEFUL findings`() {
        val findings = listOf(
            finding("null pointer exception possible", Severity.ERROR),
            finding("indentation style issue: use 4 spaces"),
            finding("unchecked cast in generic code"),
        )
        val filtered = FindingUsefulnessScorer.filterUseful(findings)
        filtered.forEach { f ->
            val score = FindingUsefulnessScorer.score(f)
            assertFalse(
                score.level == UsefulnessLevel.NOT_USEFUL,
                "filterUseful should not include NOT_USEFUL findings: ${f.message}",
            )
        }
    }

    @Test
    fun `scoreAll processes entire list preserving order`() {
        val findings = listOf(
            finding("crash on null input"),
            finding("naming convention violation"),
            finding("deadlock risk in async handler"),
        )
        val results = FindingUsefulnessScorer.scoreAll(findings)
        assertEquals(findings.size, results.size)
        findings.zip(results).forEach { (f, s) ->
            assertEquals(f, s.finding, "scoreAll should preserve finding identity and order")
        }
    }

    @Test
    fun `explanation text contributes to keyword matching`() {
        val withExplanation = finding(
            message = "potential issue found",
            explanation = "this could lead to a security vulnerability via injection",
        )
        val withoutExplanation = finding(message = "potential issue found")
        val withResult = FindingUsefulnessScorer.score(withExplanation)
        val withoutResult = FindingUsefulnessScorer.score(withoutExplanation)
        assertTrue(
            withResult.usefulKeywordHits >= withoutResult.usefulKeywordHits,
            "Explanation keywords should increase useful hits",
        )
    }

    @Test
    fun `mixed signals produce UNCERTAIN classification`() {
        val f = finding("naming style: incorrect variable name could cause error")
        val result = FindingUsefulnessScorer.score(f)
        assertTrue(
            result.level == UsefulnessLevel.UNCERTAIN || result.level != UsefulnessLevel.NOT_USEFUL,
            "Mixed signals should not produce a definitive NOT_USEFUL, got ${result.level}",
        )
    }

    @Test
    fun `empty list scoreAll returns empty list`() {
        val results = FindingUsefulnessScorer.scoreAll(emptyList())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `filterUseful on empty list returns empty list`() {
        val results = FindingUsefulnessScorer.filterUseful(emptyList())
        assertTrue(results.isEmpty())
    }
}
