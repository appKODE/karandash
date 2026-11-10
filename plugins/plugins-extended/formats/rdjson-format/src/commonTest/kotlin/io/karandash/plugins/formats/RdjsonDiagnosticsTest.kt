package io.karandash.plugins.formats

import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Location
import io.karandash.models.finding.Mismatch
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdjsonDiagnosticsTest {
    @Test
    fun `diagnostics carry suggestion metadata attachments locations and rule explanations`() =
        runTest {
            val file =
                createTestFinding(
                    ruleId = "size",
                    severity = Severity.ERROR,
                    message = "big",
                    file = "src/A.kt",
                    line = 4,
                    suggestion = "split",
                    metadata = mapOf("k" to "v")
                ).copy(attachments = listOf(Attachment("d.txt", "+a")))
            val commit = createTestFinding(
                ruleId = "size",
                severity = Severity.INFO,
                message = "c"
            ).copy(location = Location.CommitRef("abc"))
            val bare = createTestFinding(ruleId = "title", message = "t", explanation = "own")
            val report = createTestReport(findings = listOf(file, commit, bare)).copy(
                explanations = mapOf(
                    "size" to "Keep MRs small",
                    "title" to "unused"
                )
            )

            val body = RdjsonFormatModule().format(report, JsonObject(emptyMap())).body

            assertEquals(
                listOf(
                    """{"message":"big\n\nHow to fix: split\n\nk: v\n\n--- d.txt ---\n+a",""" +
                        """"location":{"path":"src/A.kt","range":{"start":{"line":4}}},""" +
                        """"severity":"ERROR","code":{"value":"size"},"original_output":"Keep MRs small"}""",
                    """{"message":"c","location":{"path":"commit:abc"},"severity":"INFO","code":{"value":"size"},"original_output":"Keep MRs small"}""",
                    """{"message":"t","severity":"WARNING","code":{"value":"title"},"original_output":"own"}""",
                ),
                Json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("diagnostics")
                    .jsonArray
                    .map { it.toString() },
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

            val body = RdjsonFormatModule().format(createTestReport(findings = listOf(finding)), JsonObject(emptyMap())).body

            val expected =
                """"bad title\n\nWhere: `feat add x` — after `feat` expected `\": \"` — found whitespace\n""" +
                    """How to fix: rename it\nFormat: `\"feat\" \": \" .+`\n\nk: v""""

            assertTrue(expected in body, body)
        }
}
