package io.karandash.models.report

import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingField
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.finding.without
import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportModelsTest {
    private val full = Finding(
        severity = Severity.ERROR,
        message = "m",
        rule = "r",
        location = Location.FileRef("a.kt", 3, 5),
        explanation = "e",
        suggestion = "s",
        metadata = mapOf(
            "custom" to "v",
            WellKnownMetadataKeys.AI_MODEL to "gpt",
            WellKnownMetadataKeys.SUGGESTED_CODE to "x",
            WellKnownMetadataKeys.WHY to "because",
        ),
        attachments = listOf(
            io.karandash.models.finding
                .Attachment("f", "c")
        ),
        fingerprint = "fp",
        priority = 5,
        confidence = 0.9f,
        relatedFindings = listOf("o"),
        errorCode = "E1",
    )

    @Test
    fun `report view with every part survives a JSON round trip`() {
        val row = FindingView("error", "x", "error", error = true, rule = "r", message = "m", where = "a.kt:3", path = "a.kt", line = 3)
        val view = ReportView(
            passed = false,
            verdict = "Failed",
            headline = "Failed",
            counts = CountsView(1, 0, 0, 1, "1 error"),
            delta = DeltaView(1, 2, 3, "1 new"),
            findings = listOf(row),
            more = 2,
            files = listOf(FileGroupView("a.kt", listOf(row))),
            details = DetailsView(1, listOf(SkippedView("r", "why")), listOf("p"), "ai", "fixed", listOf("l"), true),
            mr = MrView("!1", "t", "u"),
            version = "2",
            runNumber = 3,
            footer = "f",
            labels = LabelsView(),
        )
        val text = KarandashJson.Default.encodeToString(ReportView.serializer(), view)
        assertEquals(view, KarandashJson.Default.decodeFromString(ReportView.serializer(), text))
        assertEquals("Where", LabelsView().where)
        assertFalse(DetailsView().any)
        assertEquals("", SkippedView("r").reason)
        assertEquals("", MrView().ref)
        assertEquals(0, DeltaView().unchanged)
        assertEquals(0, CountsView().total)
    }

    @Test
    fun `field names parse case-insensitively with aliases and warnings`() {
        val parsed = FindingField.parse(listOf("Fix-Suggestions", "end_line", "ruleid", "bogus", "severity"))
        assertEquals(setOf(FindingField.FIX_SUGGESTIONS, FindingField.END_LINE), parsed.fields)
        assertEquals(3, parsed.warnings.size)
        assertTrue(parsed.warnings.any { "'severity' cannot be hidden" in it })
        assertTrue(parsed.warnings.any { "unknown field(s) 'bogus'" in it && "valid names:" in it })
        assertTrue(
            FindingField
                .parse(listOf("rule"))
                .warnings
                .single()
                .contains("'rule'")
        )
        assertTrue(FindingField.parse(emptyList()).warnings.isEmpty())
    }

    @Test
    fun `field names in dotted or spaced notation resolve like module ids`() {
        val parsed = FindingField.parse(listOf("fix.suggestions", "END LINE"))
        assertEquals(setOf(FindingField.FIX_SUGGESTIONS, FindingField.END_LINE), parsed.fields)
        assertTrue(parsed.warnings.isEmpty(), parsed.warnings.toString())
    }

    @Test
    fun `hiding fields clears exactly those parts of a finding`() {
        assertEquals(full, full.without(emptySet()))
        val trimmed = full.without(FindingField.entries.toSet())
        assertNull(trimmed.explanation)
        assertNull(trimmed.suggestion)
        assertNull(trimmed.fingerprint)
        assertNull(trimmed.confidence)
        assertNull(trimmed.errorCode)
        assertNull(trimmed.category)
        assertNull((trimmed.location as Location.FileRef).endLine)
        assertEquals(Finding.DEFAULT_PRIORITY, trimmed.priority)
        assertTrue(trimmed.attachments.isEmpty())
        assertTrue(trimmed.relatedFindings.isEmpty())
        assertTrue(trimmed.fixSuggestions.isEmpty())
        assertFalse("custom" in trimmed.metadata)
        assertFalse(WellKnownMetadataKeys.SUGGESTED_CODE in trimmed.metadata)
        assertTrue(WellKnownMetadataKeys.WHY in trimmed.metadata)

        val onlyCode = full.without(setOf(FindingField.CODE_CHANGE))
        assertFalse(WellKnownMetadataKeys.SUGGESTED_CODE in onlyCode.metadata)
        assertTrue("custom" in onlyCode.metadata)
        assertEquals(5, (onlyCode.location as Location.FileRef).endLine)
    }

    @Test
    fun `report styles pick layouts and strip internal metadata`() {
        assertEquals(ReportStyle.STANDARD, ReportStyle.DEFAULT)
        assertEquals(ReportStyle.VERBOSE, ReportStyle.parse(" Full "))
        assertEquals(ReportStyle.STANDARD, ReportStyle.parse("concise"))
        assertEquals(ReportStyle.QUIET, ReportStyle.parse("QUIET"))
        assertNull(ReportStyle.parse(null))
        assertNull(ReportStyle.parse("nope"))
        assertEquals(ReportStyle.STANDARD, ReportStyle.of("nope"))
        assertTrue(ReportStyle.VERBOSE.legacyLayout)
        assertFalse(ReportStyle.QUIET.legacyLayout)

        assertEquals(full, ReportStyle.VERBOSE.apply(full))
        val standard = ReportStyle.STANDARD.apply(full)
        assertNull(standard.fingerprint)
        assertEquals("e", standard.explanation)
        assertFalse(WellKnownMetadataKeys.AI_MODEL in standard.metadata)
        val quiet = ReportStyle.QUIET.apply(full)
        assertNull(quiet.explanation)
        assertTrue(quiet.attachments.isEmpty())
        assertTrue(WellKnownMetadataKeys.WHY in quiet.metadata)
    }
}
