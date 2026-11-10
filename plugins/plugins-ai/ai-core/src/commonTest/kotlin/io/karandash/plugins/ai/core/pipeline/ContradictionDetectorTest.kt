package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContradictionDetectorTest {
    private val detector = ContradictionDetector()

    private fun aiFinding(
        rule: String = "ai-review",
        message: String = "Some issue",
        filePath: String? = "src/A.kt",
        line: Int? = 10,
        confidence: Double? = null,
    ): Finding =
        Finding(
            severity = Severity.WARNING,
            message = message,
            rule = rule,
            location = filePath?.let { path ->
                if (line != null) Location.FileRef(path, line) else Location.FileRef(path)
            },
            metadata = if (confidence != null) mapOf("confidence" to confidence.toString()) else emptyMap(),
        )

    @Test
    fun `fewer than 2 AI findings returns unchanged`() {
        val findings = listOf(aiFinding(message = "add error handling"))
        val result = detector.detect(findings)
        assertEquals(findings, result.findings)
        assertEquals(0, result.contradictionsFound)
    }

    @Test
    fun `non-AI findings pass through unchanged`() {
        val findings = listOf(
            Finding(severity = Severity.WARNING, message = "add something", rule = "title-format"),
            Finding(severity = Severity.WARNING, message = "remove something", rule = "branch-naming"),
        )
        val result = detector.detect(findings)
        assertEquals(2, result.findings.size)
        assertEquals(0, result.contradictionsFound)
    }

    @Test
    fun `opposing findings on same file and nearby line are detected`() {
        val findings = listOf(
            aiFinding(rule = "ai-review", message = "add error handling here", line = 10, confidence = 0.7),
            aiFinding(rule = "ai-code-quality", message = "remove unnecessary try-catch", line = 12, confidence = 0.9),
        )
        val result = detector.detect(findings)
        assertEquals(1, result.findings.size)
        assertEquals(1, result.contradictionsFound)
        // lower confidence (0.7) removed
        assertTrue(result.findings.none { it.message == "add error handling here" })
        assertTrue(result.findings.any { it.message == "remove unnecessary try-catch" })
    }

    @Test
    fun `non-opposing findings on same file are kept`() {
        val findings = listOf(
            aiFinding(rule = "ai-review", message = "use more descriptive names", line = 10),
            aiFinding(rule = "ai-code-quality", message = "consider extracting method", line = 12),
        )
        val result = detector.detect(findings)
        assertEquals(2, result.findings.size)
        assertEquals(0, result.contradictionsFound)
    }

    @Test
    fun `opposing findings on different files are not contradictory`() {
        val findings = listOf(
            aiFinding(rule = "ai-review", message = "add error handling", filePath = "src/A.kt", line = 10),
            aiFinding(rule = "ai-code-quality", message = "remove unnecessary try-catch", filePath = "src/B.kt", line = 10),
        )
        val result = detector.detect(findings)
        assertEquals(2, result.findings.size)
        assertEquals(0, result.contradictionsFound)
    }

    @Test
    fun `opposing findings beyond line threshold are not contradictory`() {
        val distantLine = 10 + ContradictionDetector.LINE_PROXIMITY_THRESHOLD + 1
        val findings = listOf(
            aiFinding(rule = "ai-review", message = "add error handling", line = 10),
            aiFinding(rule = "ai-code-quality", message = "remove unnecessary try-catch", line = distantLine),
        )
        val result = detector.detect(findings)
        assertEquals(2, result.findings.size)
        assertEquals(0, result.contradictionsFound)
    }

    @Test
    fun `lower confidence finding is removed`() {
        val findings = listOf(
            aiFinding(rule = "ai-review", message = "add logging", line = 10, confidence = 0.6),
            aiFinding(rule = "ai-code-quality", message = "remove logging statement", line = 11, confidence = 0.95),
        )
        val result = detector.detect(findings)
        assertEquals(1, result.findings.size)
        // The 0.6-confidence finding is removed
        assertTrue(result.findings.none { it.message == "add logging" })
        assertTrue(result.findings.any { it.message == "remove logging statement" })
    }

    @Test
    fun `same-rule findings are not treated as contradictory`() {
        val findings = listOf(
            aiFinding(rule = "ai-review", message = "add error handling here", line = 10),
            aiFinding(rule = "ai-review", message = "remove unnecessary try-catch", line = 11),
        )
        val result = detector.detect(findings)
        assertEquals(2, result.findings.size)
        assertEquals(0, result.contradictionsFound)
    }

    @Test
    fun `when both findings lack confidence the second is removed`() {
        val findings = listOf(
            aiFinding(rule = "ai-review", message = "add validation here", line = 10, confidence = null),
            aiFinding(rule = "ai-code-quality", message = "remove validation logic", line = 11, confidence = null),
        )
        val result = detector.detect(findings)
        assertEquals(1, result.findings.size)
        assertEquals(1, result.contradictionsFound)
        // second finding (index 1) removed when no confidence on either
        assertTrue(result.findings.any { it.message == "add validation here" })
        assertTrue(result.findings.none { it.message == "remove validation logic" })
    }
}
