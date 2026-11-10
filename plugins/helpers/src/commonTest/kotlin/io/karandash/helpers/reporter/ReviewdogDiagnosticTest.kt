package io.karandash.helpers.reporter

import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewdogDiagnosticTest {
    private fun finding(
        rule: String = "r1",
        location: Location? = null,
        explanation: String? = null,
        severity: Severity = Severity.ERROR,
        suggestion: String? = null,
        metadata: Map<String, String> = emptyMap(),
        attachments: List<Attachment> = emptyList(),
    ) = Finding(
        severity = severity,
        message = "msg",
        rule = rule,
        location = location,
        explanation = explanation,
        suggestion = suggestion,
        metadata = metadata,
        attachments = attachments,
    )

    @Test
    fun `a file location with a line becomes a range and the severity is upper case`() {
        val d = finding(location = Location.FileRef("a/B.kt", line = 7), severity = Severity.WARNING).toRdDiagnostic()

        assertEquals("msg", d.message)
        assertEquals(RdLocation("a/B.kt", RdRange(RdPosition(7))), d.location)
        assertEquals("WARNING", d.severity)
        assertEquals(RdCode("r1"), d.code)
    }

    @Test
    fun `a file location without a line has no range and a commit location uses the sha`() {
        assertNull(finding(location = Location.FileRef("a.kt")).toRdDiagnostic().location?.range)
        assertEquals("commit:abc123", finding(location = Location.CommitRef("abc123")).toRdDiagnostic().location?.path)
        assertNull(finding().toRdDiagnostic().location)
    }

    @Test
    fun `suggestion metadata and attachments are appended to the message`() {
        val d = finding(
            suggestion = "do it",
            metadata = mapOf("k" to "v"),
            attachments = listOf(Attachment("map.md", "BODY")),
        ).toRdDiagnostic()

        assertTrue(d.message.startsWith("msg\n\n"), d.message)
        assertTrue("do it" in d.message, d.message)
        assertTrue("k: v" in d.message, d.message)
        assertTrue("--- map.md ---\nBODY" in d.message, d.message)
    }

    @Test
    fun `report diagnostics fall back to the rule explanation only when the finding has none`() {
        val own = finding(rule = "r1", explanation = "own")
        val bare = finding(rule = "r2")
        val findings = listOf(own, bare)
        val report = ValidationReport(
            passed = false,
            findings = findings,
            summary = findings.summarize(),
            groupedByLocation = emptyMap(),
            inlineEligible = emptyList(),
            summaryOnly = findings,
            complianceChecklist = emptyList(),
            explanations = mapOf("r1" to "rule one", "r2" to "rule two"),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
        )

        val diagnostics = report.toRdDiagnostics()

        assertEquals(listOf("own", "rule two"), diagnostics.map { it.originalOutput })
    }

    @Test
    fun `finding summary converts to the reporter summary and json instances differ in layout`() {
        val summary = listOf(finding(), finding(severity = Severity.INFO)).summarize().toReporterSummary()

        assertEquals(ReporterSummary(errors = 1, warnings = 0, info = 1), summary)
        val compact = KarandashJson.Plain.encodeToString(ReporterSummary.serializer(), summary)
        val pretty = KarandashJson.PrettyWithDefaults.encodeToString(ReporterSummary.serializer(), summary)
        assertTrue('\n' !in compact, compact)
        assertTrue('\n' in pretty, pretty)
    }
}
