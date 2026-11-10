package io.karandash.plugins.formats

import io.karandash.models.finding.Severity
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

class SlackTemplateTest {
    private val report =
        createTestReport(findings = listOf(createTestFinding(severity = Severity.ERROR, ruleId = "r1", message = "a <b> & c")))

    private suspend fun sections(
        template: String,
        partials: Map<String, String> = emptyMap(),
    ): List<String> {
        val config = JsonObject(
            mapOf("template" to JsonPrimitive(template), "partials" to JsonObject(partials.mapValues { JsonPrimitive(it.value) })),
        )
        val body = SlackFormatModule().format(report, config).body
        return Json.parseToJsonElement(body).jsonObject.getValue("blocks").jsonArray.map {
            it.jsonObject
                .getValue("text")
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content
        }
    }

    @Test
    fun `a user template and its partials render one slack escaped mrkdwn section`() =
        runTest {
            val texts = sections("{{#findings}}{{>row}}{{/findings}}", mapOf("row" to "{{rule}}: {{message}}"))
            assertEquals(listOf("r1: a &lt;b&gt; &amp; c"), texts)
        }

    @Test
    fun `a broken user template falls back to the bundled mrkdwn section`() =
        runTest {
            val text = sections("{{#findings}}").single()
            assertTrue(text.startsWith("*") && "*r1* · a &lt;b&gt; &amp; c" in text, text)
        }
}
