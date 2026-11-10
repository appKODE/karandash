package io.karandash.plugins.formats

import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Template parity for Markdown: the bundled summary passed as a user template renders the same note. */
class MarkdownTemplateTest {
    private val report =
        createTestReport(
            findings = listOf(
                createTestFinding(severity = Severity.ERROR, ruleId = "title-length", message = "Title | short"),
                createTestFinding(ruleId = "no-debug-log", message = "Debug log", file = "src/A.kt", line = 3),
            ),
        )

    private suspend fun body(config: JsonObject) = MarkdownFormatModule().format(report, config).body

    @Test
    fun `the bundled template as a user template renders the same summary`() =
        runTest {
            val bundled = body(JsonObject(emptyMap()))
            assertEquals(bundled, body(JsonObject(mapOf("template" to JsonPrimitive(MARKDOWN_SUMMARY_TEMPLATE)))))
        }

    @Test
    fun `a user template and its partials replace the summary with markdown escaped values`() =
        runTest {
            val config = buildJsonObject {
                put("template", "{{>head}}{{#findings}}\n- {{message}}{{/findings}}")
                putJsonObject("partials") { put("head", "# {{headline}}") }
            }
            val out = body(config)
            assertTrue("\n# " in out, out)
            assertTrue("- Title \\| short" in out, out)
        }

    @Test
    fun `a broken user template falls back to the bundled summary`() =
        runTest {
            assertEquals(body(JsonObject(emptyMap())), body(JsonObject(mapOf("template" to JsonPrimitive("{{#findings}}open")))))
        }
}
