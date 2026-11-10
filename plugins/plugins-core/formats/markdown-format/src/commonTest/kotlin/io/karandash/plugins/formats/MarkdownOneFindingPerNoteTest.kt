package io.karandash.plugins.formats

import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.LocationKey
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Each inline finding gets its own note with its own marker, so a reply or resolve targets one finding. */
class MarkdownOneFindingPerNoteTest {
    private fun finding(message: String) = Finding(Severity.WARNING, message, "r", Location.FileRef("src/A.kt", 3))

    @Test
    fun `two findings on one line become two notes with one marker each`() =
        runTest {
            val findings = listOf(finding("first issue"), finding("second issue"))
            val report = ValidationReport(
                passed = true,
                findings = findings,
                summary = findings.summarize(),
                groupedByLocation = mapOf(LocationKey("src/A.kt", 3) to findings),
                inlineEligible = findings,
                summaryOnly = emptyList(),
                complianceChecklist = emptyList(),
                explanations = emptyMap(),
                metadata = ReportMetadata(version = "test", timestamp = 0L),
            )

            val notes = MarkdownFormatModule().formatTyped(report, MarkdownFormatConfig()).inlineComments

            assertEquals(2, notes.size)
            assertEquals(listOf(3, 3), notes.map { it.line })
            assertEquals(
                listOf(listOf("first issue"), listOf("second issue")),
                notes.map { n ->
                    InlineFindingMarker.parse(n.body).map { it.messagePrefix }
                }
            )
            notes.forEach { assertFalse("\n---\n" in it.body, it.body) }
        }
}
