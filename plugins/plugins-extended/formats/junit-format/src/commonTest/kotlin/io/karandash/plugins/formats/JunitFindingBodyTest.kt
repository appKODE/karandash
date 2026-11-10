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

class JunitFindingBodyTest {
    private suspend fun xml(vararg findings: Finding) =
        JunitFormatModule().format(createTestReport(findings = findings.toList()), JsonObject(emptyMap())).body

    @Test
    fun `failure and system out bodies list suggestion explanation location and attachments`() =
        runTest {
            val error =
                createTestFinding(
                    ruleId = "size",
                    severity = Severity.ERROR,
                    message = "big <diff>",
                    file = "src/A.kt",
                    line = 3,
                    explanation = "why",
                    suggestion = "split",
                ).copy(attachments = listOf(Attachment("log.txt", "a & b")))
            val info = createTestFinding(
                ruleId = "size",
                severity = Severity.INFO,
                message = "fyi"
            ).copy(location = Location.CommitRef("abc"))

            assertEquals(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites name="karandash" tests="2" failures="1" errors="0">
                  <testsuite name="size" tests="2" failures="1" errors="0">
                    <testcase name="size src/A.kt:3" classname="karandash.rules.size">
                      <failure message="big &lt;diff&gt;" type="ERROR">
                Rule: size
                Severity: ERROR
                Message: big &lt;diff&gt;
                How to fix: split
                Explanation: why
                Location: src/A.kt:3
                --- log.txt ---
                a &amp; b
                      </failure>
                    </testcase>
                    <testcase name="size commit:abc" classname="karandash.rules.size">
                      <system-out>Rule: size
                Severity: INFO
                Message: fyi
                Location: commit:abc</system-out>
                    </testcase>
                  </testsuite>
                </testsuites>

                """.trimIndent(),
                xml(error, info),
            )
        }

    @Test
    fun `a fix hint renders labelled lines and stays out of the metadata`() =
        runTest {
            val finding = createTestFinding(
                ruleId = "title",
                severity = Severity.ERROR,
                message = "bad title",
                suggestion = "rename it",
                metadata = mapOf(
                    "k" to "v",
                    WellKnownMetadataKeys.MISMATCH to Mismatch("feat add x", 4, "\": \"").toMetadataValue(),
                    WellKnownMetadataKeys.EXPECTED_FORMAT to "\"feat\" \": \" .+",
                ),
            )
            val config = JsonObject(mapOf("includeAiMetadata" to JsonPrimitive(true)))

            val body = JunitFormatModule().format(createTestReport(findings = listOf(finding)), config).body

            assertTrue(
                "Message: bad title\n" +
                    "Where: `feat add x` — after `feat` expected `&quot;: &quot;` — found whitespace\nHow to fix: rename it\n" +
                    "Format: `&quot;feat&quot; &quot;: &quot; .+`\n" in body,
                body,
            )
            assertTrue("\nk: v" in body && "mismatch" !in body && "expectedFormat" !in body, body)
        }

    @Test
    fun `characters that xml 1_0 forbids are dropped from text and attributes`() =
        runTest {
            val ansi = createTestFinding(
                message = "red\u001B[31m\u0000x"
            ).copy(attachments = listOf(Attachment("ci.log", "\u001B[0mok\tdone\r\n")))

            val body = xml(ansi)

            assertEquals(
                listOf("red[31mx", "red[31mx", "[0mok\tdone\r\n"),
                listOf(
                    body.substringAfter("message=\"").substringBefore('"'),
                    body.substringAfter("Message: ").substringBefore('\n'),
                    body.substringAfter("--- ci.log ---\n").substringBefore("\n      </failure>"),
                )
            )
        }
}
