package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Analyzer rule ids carry no `ai-` prefix: the AI stages recognise them by the provenance the dispatcher stamps. */
class AiFindingRecognitionTest {
    private fun finding(
        rule: String,
        message: String,
        line: Int = 10,
        ai: Boolean,
        confidence: String? = null,
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = rule,
        location = Location.FileRef("src/A.kt", line),
        metadata = buildMap {
            if (ai) put(AiFindingMetadataKeys.AI_PROVIDER, "openai")
            confidence?.let { put(AiFindingMetadataKeys.CONFIDENCE, it) }
        },
    )

    @Test
    fun `filter drops a low-confidence analyzer finding`() {
        val analyzer = finding("review-comments", "Token read is unchecked", ai = true, confidence = "0.1")
        assertEquals(emptyList(), AiFindingFilter(FilterConfig(minConfidence = 0.5)).filter(listOf(analyzer)))
    }

    @Test
    fun `filter keeps a low-confidence deterministic finding`() {
        val rule = finding("file-size", "File is large", ai = false, confidence = "0.1")
        assertEquals(listOf(rule), AiFindingFilter(FilterConfig(minConfidence = 0.5)).filter(listOf(rule)))
    }

    @Test
    fun `contradiction detection resolves analyzer findings`() {
        val add = finding("review-comments", "add a null check", ai = true, confidence = "0.9")
        val remove = finding("code-suggestions", "remove the null check", line = 11, ai = true, confidence = "0.2")
        assertEquals(listOf(add), ContradictionDetector().detect(listOf(add, remove)).findings)
    }

    @Test
    fun `contradiction detection leaves deterministic findings alone`() {
        val add = finding("label-check", "add a label", ai = false)
        val remove = finding("title-check", "remove the prefix", line = 11, ai = false)
        assertEquals(listOf(add, remove), ContradictionDetector().detect(listOf(add, remove)).findings)
    }

    @Test
    fun `correlation merges analyzer findings on one line`() {
        val a = finding("review-comments", "Unchecked read", ai = true)
        val b = finding("risk-scoring", "Risky read", ai = true)
        val merged = FindingCorrelationEngine().mergeSameLocation(listOf(a, b)).single()
        assertEquals("review-comments,risk-scoring", merged.metadata[AiFindingMetadataKeys.CORRELATED_SOURCES])
    }

    @Test
    fun `correlation never folds deterministic findings`() {
        val a = finding("file-size", "Large", ai = false)
        val b = finding("todo-check", "TODO left", ai = false)
        val result = FindingCorrelationEngine().mergeSameLocation(listOf(a, b))
        assertEquals(listOf(a, b), result)
        assertNull(result.first().metadata[AiFindingMetadataKeys.CORRELATED_SOURCES])
    }
}
