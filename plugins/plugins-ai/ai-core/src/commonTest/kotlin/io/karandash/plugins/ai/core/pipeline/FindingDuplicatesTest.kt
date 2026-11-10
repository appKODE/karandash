package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FindingDuplicatesTest {
    private fun ai(
        message: String,
        line: Int = 10,
        severity: Severity = Severity.WARNING,
        confidence: String = "0.8",
        rule: String = "ai-review",
    ) = Finding(
        severity = severity,
        message = message,
        rule = rule,
        location = Location.FileRef("src/A.kt", line),
        metadata = mapOf("aiModel" to "m", "confidence" to confidence),
    )

    private fun thread(
        body: String,
        line: Int = 10,
        resolved: Boolean = false,
        path: String = "src/A.kt",
    ) = ReviewThread(
        id = "t",
        notes = listOf(ReviewNote(id = "n", body = body, author = "alice")),
        resolved = resolved,
        position = ReviewThreadPosition(newPath = path, newLine = line),
    )

    private val message = "The retry loop never backs off, so a failing server gets hammered."

    @Test
    fun `finding covered by an open thread within three lines is dropped`() {
        val threads = listOf(thread("retry loop never backs off, hammering the failing server", line = 13))
        assertEquals(emptyList(), FindingDuplicates.againstThreads(listOf(ai(message)), threads))
    }

    @Test
    fun `thread too far away resolved or about something else keeps the finding`() {
        val finding = ai(message)
        val keeps = listOf(
            thread("retry loop never backs off, hammering the failing server", line = 14),
            thread("retry loop never backs off, hammering the failing server", resolved = true),
            thread("retry loop never backs off, hammering the failing server", path = "src/B.kt"),
            thread("please rename this variable to something clearer"),
        )
        keeps.forEach { assertEquals(listOf(finding), FindingDuplicates.againstThreads(listOf(finding), listOf(it))) }
    }

    @Test
    fun `thread opened by the bot is left to the reconciler`() {
        val bot = thread("retry loop never backs off, hammering the failing server\n<!-- karandash-finding {} -->")
        assertEquals(listOf(ai(message)), FindingDuplicates.againstThreads(listOf(ai(message)), listOf(bot)))
    }

    @Test
    fun `same line repeats keep the most severe then most confident`() {
        val low = ai("The retry loop never backs off, so servers get hammered.", severity = Severity.INFO)
        val warn = ai(message, confidence = "0.5")
        val other = ai("Unrelated naming concern about the helper function here.")
        assertEquals(listOf(warn, other), FindingDuplicates.inRun(listOf(low, warn, other)).findings)
        val sameSeverity = listOf(ai(message, confidence = "0.5"), ai(message + " Really.", confidence = "0.9"))
        assertEquals(listOf(sameSeverity[1]), FindingDuplicates.inRun(sameSeverity).findings)
    }

    @Test
    fun `similar text far away or from another rule with cross-rule off is no in-run duplicate`() {
        val far = listOf(ai(message, line = 10), ai(message + " Really.", line = 20))
        assertEquals(far, FindingDuplicates.inRun(far).findings)
        val otherRule = listOf(ai(message), ai(message + " Really.", rule = "ai-other"))
        assertEquals(otherRule, FindingDuplicates.inRun(otherRule, DedupConfig(crossRule = false)).findings)
    }

    @Test
    fun `a similar finding of another rule within the line window merges into the more severe one`() {
        val warn = ai(message, line = 12, rule = "ai-other")
        val error = ai(message + " Really.", line = 10, severity = Severity.ERROR)
        val result = FindingDuplicates.inRun(listOf(warn, error))
        assertEquals(listOf("ai-review"), result.findings.map { it.rule })
        assertEquals("ai-review,ai-other", result.findings.single().metadata[AiFindingMetadataKeys.MERGED_RULES])
        assertEquals(listOf(StageRecord.MERGE, StageRecord.KEEP), result.records.map { it.decision })
        assertTrue(
            result.records
                .first()
                .reason
                .contains("within 3 lines")
        )
    }

    @Test
    fun `same-file findings with the same message collapse into one listing every line`() {
        val result = FindingDuplicates.inRun(listOf(ai(message, line = 10), ai(message, line = 40), ai(message, line = 70)))
        assertEquals(1, result.findings.size)
        assertEquals("10,40,70", result.findings.single().metadata[AiFindingMetadataKeys.MERGED_LINES])
    }

    @Test
    fun `a review comment never merges with its code-suggestions twin`() {
        val comment = ai(message, rule = AiRuleIds.REVIEW_COMMENTS)
        val twin = ai(message, rule = AiRuleIds.CODE_SUGGESTIONS)
        assertEquals(listOf(comment, twin), FindingDuplicates.inRun(listOf(comment, twin)).findings)
    }

    @Test
    fun `a negative line window is a config error naming the key`() {
        val error = assertFailsWith<ConfigParseException> { DedupConfig(lineWindow = -1).validated() }
        assertTrue(error.message.orEmpty().contains("ai.dedup.lineWindow"))
    }

    @Test
    fun `two identical findings on one line keep one`() {
        val finding = ai(message)
        assertEquals(listOf(finding), FindingDuplicates.inRun(listOf(finding, finding.copy())).findings)
    }
}
