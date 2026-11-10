package io.karandash.cli.pipeline

import io.karandash.cli.errors.ExitCodes
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CiLogSummaryTest {
    private val error = Finding(Severity.ERROR, "Title must start with a ticket\nsecond line", "titleFormat")
    private val warning = Finding(Severity.WARNING, "Large file", "fileSize", Location.FileRef("a/B.kt", 12))

    @Test
    fun `failed run lists findings errors first and counts them in the result line`() {
        val lines = CiLogSummary.lines(
            listOf(warning, error, warning),
            ExitCodes.VALIDATION_FAILED,
            listFindings = true,
            platformNote = true
        )
        assertEquals(
            listOf(
                "Findings (3):",
                "  error titleFormat: Title must start with a ticket…",
                "  warning fileSize [a/B.kt:12]: Large file",
                "  warning fileSize [a/B.kt:12]: Large file",
                "Result: FAILED — 1 error, 2 warnings (details in the MR note)",
            ),
            lines,
        )
    }

    @Test
    fun `passed run without findings prints only the result line`() {
        val lines = CiLogSummary.lines(emptyList(), ExitCodes.SUCCESS, listFindings = true, platformNote = true)
        assertEquals(listOf("Result: PASSED — no findings"), lines)
    }

    @Test
    fun `list is capped with a more line`() {
        val many = List(CiLogSummary.MAX_LISTED + 5) { warning }
        val lines = CiLogSummary.lines(many, ExitCodes.SUCCESS, listFindings = true, platformNote = false)
        assertEquals("  +5 more", lines[lines.size - 2])
        assertEquals("Result: PASSED — 25 warnings", lines.last())
    }

    @Test
    fun `list is left out when the console report already printed findings`() {
        val lines = CiLogSummary.lines(listOf(error), ExitCodes.VALIDATION_FAILED, listFindings = false, platformNote = false)
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("Result: FAILED"))
    }
}
