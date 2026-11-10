package io.karandash.plugins.formats

import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.PresentationKeys
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsolePresentationSectionTest {
    private fun reportOf(findings: List<Finding>): ValidationReport {
        val summary = findings.summarize()
        return ValidationReport(
            passed = summary.errors == 0,
            findings = findings,
            summary = summary,
            groupedByLocation = emptyMap(),
            inlineEligible = emptyList(),
            summaryOnly = findings,
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
        )
    }

    @Test
    fun sectionFindingsRenderInTheirOwnBlockNotUnderSeverity() =
        runTest {
            val sectioned = Finding(
                severity = Severity.INFO,
                message = "probe score 7",
                rule = "probe",
                metadata = mapOf(PresentationKeys.SECTION to "Probe"),
            )
            val plain = Finding(severity = Severity.INFO, message = "plain note", rule = "other")

            val full = JsonObject(mapOf("style" to JsonPrimitive("full")))
            val body = ConsoleFormatModule().format(reportOf(listOf(sectioned, plain)), full).body

            assertContains(body, "Probe (1):\n  - [probe] probe score 7")
            assertContains(body, "Info (1):\n  - [other] plain note")
            assertFalse(body.contains("Info (2)"))
        }

    @Test
    fun sectionTitleIsTheHeadingAndSectionOrderSortsSections() =
        runTest {
            fun routed(
                section: String,
                vararg extra: Pair<String, String>,
            ) = Finding(
                severity = Severity.INFO,
                message = "in $section",
                rule = "r",
                metadata = mapOf(PresentationKeys.SECTION to section, *extra),
            )
            val findings = listOf(
                routed("unordered"),
                routed("late", PresentationKeys.SECTION_ORDER to "20"),
                routed("early", PresentationKeys.SECTION_ORDER to "10", PresentationKeys.SECTION_TITLE to "Early bird"),
            )

            val body = ConsoleFormatModule().format(reportOf(findings), JsonObject(emptyMap())).body

            assertContains(body, "Early bird (1):\n  - [r] in early")
            assertFalse(body.contains("early (1)"))
            val positions = listOf("Early bird (1)", "late (1)", "unordered (1)").map { body.indexOf(it) }
            assertTrue(positions.all { it >= 0 } && positions == positions.sorted(), "section order: $positions")
        }
}
