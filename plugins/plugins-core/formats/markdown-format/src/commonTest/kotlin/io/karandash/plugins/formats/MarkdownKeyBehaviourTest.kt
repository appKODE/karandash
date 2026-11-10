package io.karandash.plugins.formats

import io.karandash.api.finding.EnricherProvenance
import io.karandash.api.finding.LocationKey
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One behaviour test per `markdown` format key, set through the config JSON the pipeline hands the module. */
class MarkdownKeyBehaviourTest {
    private val summaryFinding =
        Finding(
            severity = Severity.INFO,
            message = "Adds login.",
            rule = "mr-summary",
            metadata =
                mapOf(
                    WellKnownMetadataKeys.IS_MR_SUMMARY to "true",
                    WellKnownMetadataKeys.WALKTHROUGH to "| Unit | Files |\n|---|---|\n| `app` | `A.kt` |\n",
                ),
        )
    private val inlineFinding =
        Finding(
            severity = Severity.WARNING,
            message = "inline issue",
            rule = "aiReview",
            location = Location.FileRef("src/A.kt", 3),
            metadata = mapOf(WellKnownMetadataKeys.WHY to "aiReview on gpt-4o"),
        )
    private val provenance =
        ReviewProvenance(enrichers = listOf(EnricherProvenance("ci", ProvenanceStatus.OK, tokens = 120)))
    private val report =
        listOf(summaryFinding, inlineFinding).let { findings ->
            ValidationReport(
                passed = true,
                findings = findings,
                summary = findings.summarize(),
                groupedByLocation = mapOf(LocationKey("src/A.kt", 3) to listOf(inlineFinding)),
                inlineEligible = listOf(inlineFinding),
                summaryOnly = listOf(summaryFinding),
                complianceChecklist = emptyList(),
                explanations = emptyMap(),
                metadata = ReportMetadata(version = "test", timestamp = 0L),
                provenance = provenance,
            )
        }

    private suspend fun format(config: JsonElement) = MarkdownFormatModule().format(report, config)

    private fun transparency(
        key: String,
        value: JsonElement,
    ) = buildJsonObject { put("transparency", buildJsonObject { put(key, value) }) }

    @Test
    fun `preview true drops the comment markers and inline comments`() =
        runTest {
            val formatted = format(buildJsonObject { put("preview", true) })

            assertNull(formatted.commentMarker)
            assertTrue(formatted.inlineComments.isEmpty())
            assertEquals(1, format(buildJsonObject { }).inlineComments.size)
        }

    @Test
    fun `transparency object off summary removes the provenance block`() =
        runTest {
            assertTrue("<details><summary>" in format(buildJsonObject { }).body)
            assertFalse("Provenance" in format(transparency("summary", JsonPrimitive("off"))).body.lowercase())
        }

    @Test
    fun `transparency summary expanded opens the provenance block`() =
        runTest {
            assertTrue("<details open>" in format(transparency("summary", JsonPrimitive("expanded"))).body)
            assertFalse("<details open>" in format(buildJsonObject { }).body)
        }

    @Test
    fun `transparency flowDiagram true adds the mermaid diagram to the summary`() =
        runTest {
            assertTrue("```mermaid" in format(transparency("flowDiagram", JsonPrimitive(true))).body)
            assertFalse("```mermaid" in format(buildJsonObject { }).body)
        }

    @Test
    fun `transparency inlineWhy true adds the why footer to inline comments`() =
        runTest {
            val verbose = "style" to JsonPrimitive("verbose")
            val on = format(JsonObject(transparency("inlineWhy", JsonPrimitive(true)) + verbose)).inlineComments.single().body
            val off = format(JsonObject(mapOf(verbose))).inlineComments.single().body

            assertTrue("Why: aiReview on gpt-4o" in on, on)
            assertFalse("Why:" in off, off)
        }

    @Test
    fun `walkthrough collapsed shows the walkthrough section`() =
        runTest {
            val on = format(buildJsonObject { put("walkthrough", "collapsed") }).body

            assertTrue("<details><summary>Walkthrough</summary>" in on, on)
            assertFalse("Walkthrough</summary>" in format(buildJsonObject { }).body)
        }
}
