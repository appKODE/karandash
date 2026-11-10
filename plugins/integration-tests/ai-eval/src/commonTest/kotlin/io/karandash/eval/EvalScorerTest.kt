package io.karandash.eval

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvalScorerTest {
    private fun finding(
        path: String? = null,
        line: Int? = null,
        metadata: Map<String, String> = emptyMap(),
    ) = Finding(Severity.WARNING, "msg", "a", path?.let { Location.FileRef(it, line) }, metadata = metadata)

    @Test
    fun `line inside the tolerance window matches`() {
        val label = EvalLabel("a", file = "src/A.kt", startLine = 10, endLine = 12)
        assertTrue(EvalScorer.matches(finding("./src/A.kt", 15), label))
        assertTrue(EvalScorer.matches(finding("src/A.kt", 7), label))
        assertFalse(EvalScorer.matches(finding("src/A.kt", 16), label))
        assertFalse(EvalScorer.matches(finding("src/B.kt", 10), label))
        assertFalse(EvalScorer.matches(finding(), label))
    }

    @Test
    fun `finding without a line matches on file alone`() {
        assertTrue(EvalScorer.matches(finding("src/A.kt"), EvalLabel("a", file = "src/A.kt", startLine = 3)))
    }

    @Test
    fun `category matches a metadata value or list item ignoring case`() {
        val label = EvalLabel("a", category = "bug")
        assertTrue(EvalScorer.matches(finding(metadata = mapOf("category" to "Bug")), label))
        assertTrue(EvalScorer.matches(finding(metadata = mapOf("labels" to "feature, bug")), label))
        assertFalse(EvalScorer.matches(finding(metadata = mapOf("summary" to "a bug fix")), label))
    }

    @Test
    fun `duplicates are neither true nor false positives`() {
        val labels = listOf(EvalLabel("a", file = "src/A.kt"), EvalLabel("a", file = "src/C.kt"), EvalLabel("b"))
        val findings = listOf(finding("src/A.kt", 1), finding("src/A.kt", 2), finding("src/B.kt", 1))
        val outcome = EvalScorer.score("a", findings, labels)
        assertEquals(EvalScore(tp = 1, fp = 1, fn = 1, duplicates = 1), outcome.score)
        assertEquals(2, outcome.misses.size)
    }

    @Test
    fun `duplicates and anchor accuracy are counted`() {
        val labels = listOf(
            EvalLabel("a", file = "src/A.kt", startLine = 10, endLine = 12),
            EvalLabel("a", file = "src/B.kt", startLine = 20),
        )
        val findings = listOf(finding("src/A.kt", 11), finding("src/A.kt", 11), finding("src/B.kt", 22))
        val score = EvalScorer.score("a", findings, labels).score
        assertEquals(EvalScore(tp = 2, duplicates = 1, anchorable = 2, anchored = 1), score)
        assertEquals(1.0 / 3, score.duplicateRate, 1e-9)
        assertEquals(0.5, score.anchorAccuracy)
        assertEquals(1.0, EvalScore().anchorAccuracy)
    }

    @Test
    fun `empty sides score perfect precision and recall`() {
        val score = EvalScorer.score("a", emptyList(), emptyList()).score
        assertEquals(1.0, score.precision)
        assertEquals(1.0, score.recall)
        assertEquals(0.5, EvalScore(tp = 1, fp = 1).precision)
        assertEquals(0.0, EvalScore(fp = 1, fn = 1).f1)
    }
}
