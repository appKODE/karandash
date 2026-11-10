package io.karandash.plugins.formats

import io.karandash.api.finding.InlineCommentData
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An inline note is a header with a one-line title plus at most three one-line body paragraphs. */
class MarkdownTerseInlineTest {
    private fun finding(
        message: String,
        metadata: Map<String, String> = emptyMap(),
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = "review-comments",
        location = Location.FileRef("src/A.kt", 3),
        metadata = metadata,
    )

    /** Body paragraphs: everything after the header line and before the marker, outside the suggestion block. */
    private fun bodyLines(body: String): List<String> {
        val withoutBlock = body.substringBefore(InlineCommentData.SUGGESTION_START) +
            body.substringAfter(InlineCommentData.SUGGESTION_END, "")
        return withoutBlock.lines().drop(1).filter { it.isNotBlank() && !it.startsWith("<!-- karandash-finding") }
    }

    @Test
    fun `a verbose finding is capped at three body lines`() {
        val verbose = finding(
            "Title line\nsecond\nthird\n\nfourth",
            metadata = mapOf(WellKnownMetadataKeys.DIMENSION to "correctness"),
        ).copy(suggestion = "Do this\nand that", explanation = "Because\nreasons")

        val body = formatInlineCommentBody(verbose)

        assertTrue(body.lines().first().endsWith(" · Title line"), body)
        assertEquals(listOf("second third fourth", "**How to fix:** Do this and that", "ℹ️ Because reasons"), bodyLines(body))
    }

    @Test
    fun `a fenced block in the message is dropped and its prose kept on one line`() {
        val fenced =
            finding("Title\nThe lookup is unguarded:\n```kotlin\nval u = repo.find(id)!!\nreturn u.name\n```\nso deleted users crash.")

        assertEquals(listOf("The lookup is unguarded: so deleted users crash."), bodyLines(formatInlineCommentBody(fenced)))
    }

    @Test
    fun `a short finding keeps every body line`() {
        val short = finding("Title").copy(suggestion = "Fix it.")

        assertEquals(listOf("**How to fix:** Fix it."), bodyLines(formatInlineCommentBody(short)))
    }

    @Test
    fun `real suggested code gets a suggestion block and prose does not`() {
        val code = finding("t", mapOf(WellKnownMetadataKeys.SUGGESTED_CODE to "val x = y ?: return"))
        val prose = finding("t", mapOf(WellKnownMetadataKeys.SUGGESTED_CODE to "Rename this to something clearer."))

        assertTrue(InlineCommentData.SUGGESTION_START in formatInlineCommentBody(code))
        assertFalse(InlineCommentData.SUGGESTION_START in formatInlineCommentBody(prose))
    }
}
