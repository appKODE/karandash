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

/** Template parity for HTML: the bundled page passed as a user template renders the same email. */
class HtmlTemplateTest {
    private val report =
        createTestReport(findings = listOf(createTestFinding(severity = Severity.ERROR, ruleId = "r1", message = "a <b>")))

    private suspend fun format(config: JsonObject) = HtmlFormatModule().format(report, config)

    @Test
    fun `the bundled template as a user template renders the same page`() =
        runTest {
            val bundled = format(JsonObject(emptyMap()))
            val custom = format(JsonObject(mapOf("template" to JsonPrimitive(HTML_TEMPLATE))))
            assertEquals(bundled.body, custom.body)
            assertEquals(bundled.metadata, custom.metadata)
        }

    @Test
    fun `a user template and its partials replace the page with html escaped values`() =
        runTest {
            val config = buildJsonObject {
                put("template", "<ul>{{#findings}}{{>row}}{{/findings}}</ul>")
                putJsonObject("partials") { put("row", "<li>{{message}}</li>") }
            }
            val out = format(config).body
            assertEquals("<ul><li>a &lt;b&gt;</li></ul>", out.trim(), out)
        }

    @Test
    fun `a broken user template falls back to the bundled page`() =
        runTest {
            assertEquals(format(JsonObject(emptyMap())).body, format(JsonObject(mapOf("template" to JsonPrimitive("{{/x}}")))).body)
        }
}
