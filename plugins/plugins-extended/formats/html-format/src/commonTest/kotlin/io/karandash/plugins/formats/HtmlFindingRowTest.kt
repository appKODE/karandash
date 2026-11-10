package io.karandash.plugins.formats

import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Mismatch
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlFindingRowTest {
    private suspend fun html(vararg findings: Finding) =
        HtmlFormatModule()
            .format(createTestReport(findings = findings.toList()), JsonObject(mapOf("style" to JsonPrimitive("verbose"))))
            .body

    private fun rows(body: String) = Regex("<tr><td.*?</tr>").findAll(body).map { it.value }.toList()

    @Test
    fun `finding rows carry severity colour location suggestion metadata and attachments escaped`() =
        runTest {
            val warning =
                createTestFinding(
                    ruleId = "r'1",
                    message = "a \"b\"",
                    suggestion = "<fix>",
                    metadata = mapOf("k" to "v&w"),
                    file = "src/A.kt",
                ).copy(attachments = listOf(Attachment("d<1>.txt", "x < y")))
            val info = createTestFinding(severity = Severity.INFO, message = "i").copy(location = Location.CommitRef("abc123"))
            val error = createTestFinding(severity = Severity.ERROR, message = "e", file = "src/B.kt", line = 7)

            val body = html(warning, info, error)

            assertEquals(
                listOf(
                    "<tr><td style=\"color: #ffc107; font-weight: bold;\">WARNING</td><td><code>r&#39;1</code></td>" +
                        "<td>a &quot;b&quot;<br><em>How to fix: &lt;fix&gt;</em><br><small><em>k: v&amp;w</em></small>" +
                        "<br><details><summary>d&lt;1&gt;.txt</summary><pre>x &lt; y</pre></details></td><td>src/A.kt</td></tr>",
                    "<tr><td style=\"color: #17a2b8; font-weight: bold;\">INFO</td><td><code>test-rule</code></td>" +
                        "<td>i</td><td>commit:abc123</td></tr>",
                    "<tr><td style=\"color: #dc3545; font-weight: bold;\">ERROR</td><td><code>test-rule</code></td>" +
                        "<td>e</td><td>src/B.kt:7</td></tr>",
                ),
                rows(body),
            )
            assertTrue("Errors: 1 | Warnings: 1 | Info: 1" in body, body)
        }

    @Test
    fun `a fix hint renders labelled lines and stays out of the metadata`() =
        runTest {
            val finding =
                createTestFinding(
                    message = "bad title",
                    suggestion = "rename it",
                    metadata = mapOf(
                        "k" to "v",
                        WellKnownMetadataKeys.MISMATCH to Mismatch("feat add x", 4, "\": \"").toMetadataValue(),
                        WellKnownMetadataKeys.EXPECTED_FORMAT to "\"feat\" \": \" .+",
                    ),
                )

            val row = rows(html(finding)).single()

            assertTrue(
                "<td>bad title<br><em>Where: `feat add x` — after `feat` expected `&quot;: &quot;` — found whitespace</em>" +
                    "<br><em>How to fix: rename it</em><br><em>Format: `&quot;feat&quot; &quot;: &quot; .+`</em>" +
                    "<br><small><em>k: v</em></small></td>" in row,
                row,
            )
        }

    @Test
    fun `report without findings shows the passed banner`() =
        runTest {
            val body = html()

            assertTrue("Karandash MR Validation: PASSED" in body, body)
            assertTrue("All validation checks passed!" in body, body)
            assertTrue(rows(body).isEmpty())
        }
}
