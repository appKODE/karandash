package io.karandash.plugins.formats

import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.LocationKey
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `markdown.preview` formats only - no markers or inline comments, so platform outputs post nothing. */
class MarkdownPreviewTest {
    private val finding = Finding(
        severity = Severity.WARNING,
        message = "inline issue",
        rule = "r",
        location = Location.FileRef(path = "src/A.kt", line = 3),
    )

    private val report = listOf(finding).let { findings ->
        val summary = findings.summarize()
        ValidationReport(
            passed = true,
            findings = findings,
            summary = summary,
            groupedByLocation = mapOf(LocationKey("src/A.kt", 3) to findings),
            inlineEligible = findings,
            summaryOnly = emptyList(),
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
        )
    }

    @Test
    fun previewDropsMarkersAndInlineComments() =
        runTest {
            val formatted = MarkdownFormatModule().formatTyped(report, MarkdownFormatConfig(preview = true))

            assertNull(formatted.commentMarker)
            assertNull(formatted.inlineMarker)
            assertTrue(formatted.inlineComments.isEmpty())
            assertTrue(formatted.body.isNotBlank())
        }

    @Test
    fun defaultKeepsMarkersAndInlineComments() =
        runTest {
            val formatted = MarkdownFormatModule().formatTyped(report, MarkdownFormatConfig())

            assertEquals(MarkdownFormatModule.MARKER, formatted.commentMarker)
            assertEquals(1, formatted.inlineComments.size)
        }

    @Test
    fun suggestedCodeIsWrappedInSuggestionMarkers() {
        val withCode = finding.copy(metadata = mapOf(WellKnownMetadataKeys.SUGGESTED_CODE to "val x = 1"))

        val body = formatInlineCommentBody(withCode)

        val expected = "${InlineCommentData.SUGGESTION_START}\n```diff\n+ val x = 1\n```\n${InlineCommentData.SUGGESTION_END}"
        assertTrue(expected in body, body)
    }
}
