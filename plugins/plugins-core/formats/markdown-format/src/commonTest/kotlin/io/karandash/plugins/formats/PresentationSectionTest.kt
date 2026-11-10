package io.karandash.plugins.formats

import io.karandash.models.finding.Finding
import io.karandash.models.finding.PresentationKeys
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresentationSectionTest {
    private fun finding(
        message: String,
        vararg meta: Pair<String, String>,
    ) = Finding(severity = Severity.INFO, message = message, rule = "r", metadata = mapOf(*meta))

    @Test
    fun sectionKeyRoutesFindingIntoNamedSection() {
        val routed = finding("probe score", PresentationKeys.SECTION to "Probe")
        val plain = finding("plain")

        val partitioned = partitionFindings(listOf(routed, plain))

        assertEquals(mapOf("Probe" to listOf(routed)), partitioned.sections)
        assertEquals(listOf(plain), partitioned.regular)
    }

    @Test
    fun legacyFlagWinsOverSectionKey() {
        val summary =
            finding(
                "summary",
                WellKnownMetadataKeys.IS_MR_SUMMARY to "true",
                PresentationKeys.SECTION to "Probe",
            )

        val partitioned = partitionFindings(listOf(summary))

        assertEquals(listOf(summary), partitioned.mrSummary)
        assertTrue(partitioned.sections.isEmpty())
    }

    @Test
    fun sectionKeyIsNotRenderedAsMetadata() {
        assertTrue(PresentationKeys.SECTION !in METADATA_DISPLAY_KEYS)
        assertTrue(PresentationKeys.SECTION_TITLE !in METADATA_DISPLAY_KEYS)
        assertTrue(PresentationKeys.SECTION_ORDER !in METADATA_DISPLAY_KEYS)
    }

    @Test
    fun sectionsAreOrderedBySectionOrderAndTitledBySectionTitle() {
        val b = finding("b", PresentationKeys.SECTION to "b")
        val a = finding(
            "a",
            PresentationKeys.SECTION to "a",
            PresentationKeys.SECTION_ORDER to "1",
            PresentationKeys.SECTION_TITLE to "A title"
        )

        assertEquals(listOf("A title" to listOf(a), "b" to listOf(b)), PresentationKeys.sections(listOf(b, a)))
    }
}
