package io.karandash.plugins.formats

import io.karandash.api.finding.ValidationReport
import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Mismatch
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackFindingTextTest {
    private suspend fun sectionTexts(report: ValidationReport): List<String> {
        val body = SlackFormatModule().format(report, JsonObject(mapOf("style" to JsonPrimitive("verbose")))).body
        return Json
            .parseToJsonElement(body)
            .jsonObject
            .getValue("blocks")
            .jsonArray
            .mapNotNull { block ->
                block.jsonObject["text"]
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    ?: block.jsonObject["elements"]
                        ?.jsonArray
                        ?.first()
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.content
            }
    }

    @Test
    fun `finding text renders suggestion metadata and attachments`() {
        val finding =
            createTestFinding(
                ruleId = "size",
                message = "Too big",
                suggestion = "Split it",
                metadata = mapOf("files" to "42", "limit" to "10"),
            ).copy(attachments = listOf(Attachment("diff.txt", "+a")))

        assertEquals(
            ":x: *size*\nToo big\nHow to fix: Split it\n_files: 42_ | _limit: 10_\n*diff.txt*\n```+a```",
            buildFindingText(":x:", finding),
        )
    }

    @Test
    fun `a fix hint renders labelled lines and stays out of the metadata`() {
        val finding =
            createTestFinding(
                ruleId = "title",
                message = "bad title",
                suggestion = "rename it",
                metadata = mapOf(
                    "k" to "v",
                    WellKnownMetadataKeys.MISMATCH to Mismatch("feat add x", 4, "\": \"").toMetadataValue(),
                    WellKnownMetadataKeys.EXPECTED_FORMAT to "\"feat\" \": \" .+",
                ),
            )

        assertEquals(
            ":x: *title*\nbad title\nWhere: `feat add x` — after `feat` expected `\": \"` — found whitespace\nHow to fix: rename it" +
                "\nFormat: `\"feat\" \": \" .+`\n_k: v_",
            buildFindingText(":x:", finding),
        )
    }

    @Test
    fun `control characters from mr text are escaped so they cannot ping channels or forge links`() {
        val finding = createTestFinding(ruleId = "title", message = "Title <!channel> & <https://evil|docs> a > b")

        assertEquals(
            ":x: *title*\nTitle &lt;!channel&gt; &amp; &lt;https://evil|docs&gt; a &gt; b",
            buildFindingText(":x:", finding),
        )
    }

    @Test
    fun `section text stays within the slack 3000 character limit`() {
        val big = "x".repeat(2900)
        val finding =
            createTestFinding(message = "m").copy(attachments = listOf(Attachment("a.txt", big), Attachment("b.txt", big)))

        assertTrue(buildFindingText(":x:", finding).length <= 3000)
    }

    @Test
    fun `passed report and more than ten findings render summary and overflow note`() =
        runTest {
            val passed = sectionTexts(createTestReport(findings = emptyList()))
            val many =
                sectionTexts(
                    createTestReport(
                        findings =
                            List(12) { createTestFinding(message = "m$it", severity = Severity.entries[it % 3]) },
                    ),
                )

            assertEquals("Karandash Validation: :white_check_mark: PASSED", passed.first())
            assertEquals("All validation checks passed! :tada:", passed[1])
            assertEquals(listOf(":x:", ":warning:", ":information_source:"), many.slice(2..4).map { it.substringBefore(' ') })
            assertEquals("_... and 2 more findings_", many[many.size - 2])
        }
}
