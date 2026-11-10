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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The standard style (default): alert verdict, compact table, footer; inline notes headed by emoji, rule and title. */
class MarkdownCompactLayoutTest {
    private fun report(findings: List<Finding>) =
        ValidationReport(
            passed = findings.none { it.severity == Severity.ERROR },
            findings = findings,
            summary = findings.summarize(),
            groupedByLocation = findings.groupBy {
                (it.location as? Location.FileRef).let { ref ->
                    LocationKey(ref?.path.orEmpty(), ref?.line)
                }
            },
            inlineEligible = findings.filter { it.location is Location.FileRef },
            summaryOnly = emptyList(),
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
        )

    private val error =
        Finding(Severity.ERROR, "Title is too short", "title-length", suggestion = "Use at least 10 characters")
    private val warning =
        Finding(Severity.WARNING, "Debug log left in", "no-debug-log", location = Location.FileRef("src/A.kt", 3))

    @Test
    fun `a failing run opens with a caution alert and a table row per finding`() =
        runTest {
            val body = MarkdownFormatModule().format(report(listOf(error, warning)), buildJsonObject { }).body

            assertTrue("\n> [!caution]\n" in body, body)
            assertTrue("| ❌ |" in body, body)
            assertTrue("`title-length` | Title is too short | Use at least 10 characters |" in body, body)
            assertTrue("| ⚠️ |" in body, body)
            assertFalse("## Karandash Compliance" in body, body)
        }

    @Test
    fun `the Where column is left out when no finding names a file or a commit`() =
        runTest {
            val mrOnly = MarkdownFormatModule().format(report(listOf(error)), buildJsonObject { }).body
            val withFile = MarkdownFormatModule().format(report(listOf(error, warning)), buildJsonObject { }).body

            assertTrue("| | Rule | Finding | How to fix |\n|---|---|---|---|" in mrOnly, mrOnly)
            assertTrue("| ❌ | `title-length` |" in mrOnly, mrOnly)
            assertTrue("| Where |" in withFile, withFile)
            assertTrue("| ❌ | MR | `title-length` |" in withFile, withFile)
        }

    @Test
    fun `a passing run opens with a tip alert`() =
        runTest {
            val body = MarkdownFormatModule().format(report(listOf(warning)), buildJsonObject { }).body

            assertTrue("\n> [!tip]\n" in body, body)
        }

    @Test
    fun `the merge request url links file rows and the verbose style restores the full layout`() =
        runTest {
            val fileError = error.copy(location = Location.FileRef("src/B.kt", 7))
            val config = buildJsonObject {
                putJsonObject("mr") {
                    put("ref", "!12")
                    put("title", "Add login")
                    put("url", "https://gitlab.example.com/g/p/-/merge_requests/12")
                    putJsonObject("lineLinks") { put("src/B.kt:7", "https://gitlab.example.com/g/p/-/merge_requests/12/diffs#b_7_7") }
                }
            }
            val body = MarkdownFormatModule().format(report(listOf(fileError, warning)), config).body
            val verbose = MarkdownFormatModule().format(report(listOf(fileError)), buildJsonObject { put("style", "verbose") }).body

            assertTrue("[B.kt:7](https://gitlab.example.com/g/p/-/merge_requests/12/diffs#b_7_7 \"src/B.kt\")" in body, body)
            assertTrue("[A.kt:3](https://gitlab.example.com/g/p/-/merge_requests/12/diffs \"src/A.kt\")" in body, body)
            assertTrue("## Karandash Compliance" in verbose, verbose)
            assertFalse("> [!caution]" in verbose, verbose)
        }

    @Test
    fun `the summary stays within the comment size limit`() =
        runTest {
            val many = (1..400).map { Finding(Severity.ERROR, "Message number $it ".repeat(4), "rule-$it") }
            val body = MarkdownFormatModule().format(report(many), buildJsonObject { }).body

            assertTrue(body.encodeToByteArray().size <= SUMMARY_MAX_BYTES, "${body.encodeToByteArray().size}")
            assertTrue("\n> [!caution]\n" in body, body)
        }

    @Test
    fun `the inline note starts with the emoji rule and title and keeps the marker`() =
        runTest {
            val note = MarkdownFormatModule()
                .format(report(listOf(warning)), buildJsonObject { })
                .inlineComments
                .single()
                .body

            assertTrue(note.lines()[1].startsWith("⚠️ **`no-debug-log`** · Debug log left in"), note)
            assertEquals(InlineFindingMarker.of(warning), note.lines().last())
        }
}
