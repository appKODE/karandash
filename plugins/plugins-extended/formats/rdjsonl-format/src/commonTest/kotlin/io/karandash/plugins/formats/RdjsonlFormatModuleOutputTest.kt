package io.karandash.plugins.formats

import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Mismatch
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdjsonlFormatModuleOutputTest {
    private val module = RdjsonlFormatModule()

    private suspend fun body(
        findings: List<Finding>,
        explanations: Map<String, String> = emptyMap(),
    ) = module.format(createTestReport(findings = findings).copy(explanations = explanations), JsonObject(emptyMap())).body

    @Test
    fun `empty report gives an empty body`() =
        runTest {
            val report = module.format(createTestReport(findings = emptyList()), JsonObject(emptyMap()))

            assertEquals("rdjsonl", report.formatId)
            assertEquals("", report.body)
        }

    @Test
    fun `one diagnostic per line with location severity and code`() =
        runTest {
            val findings =
                listOf(
                    Finding(severity = Severity.ERROR, message = "bad", rule = "titleFormat", location = Location.FileRef("src/A.kt", 7)),
                    Finding(severity = Severity.WARNING, message = "file", rule = "size", location = Location.FileRef("b.kt")),
                    Finding(severity = Severity.INFO, message = "c", rule = "commits", location = Location.CommitRef("abc")),
                    Finding(severity = Severity.INFO, message = "none", rule = "labels"),
                )

            assertEquals(
                listOf(
                    """{"message":"bad","location":{"path":"src/A.kt","range":{"start":{"line":7}}},"severity":"ERROR","code":{"value":"titleFormat"}}""",
                    """{"message":"file","location":{"path":"b.kt"},"severity":"WARNING","code":{"value":"size"}}""",
                    """{"message":"c","location":{"path":"commit:abc"},"severity":"INFO","code":{"value":"commits"}}""",
                    """{"message":"none","severity":"INFO","code":{"value":"labels"}}""",
                ),
                body(findings).lines().dropLast(1),
            )
        }

    @Test
    fun `message carries suggestion metadata and attachments`() =
        runTest {
            val finding =
                Finding(
                    severity = Severity.WARNING,
                    message = "m",
                    rule = "r",
                    suggestion = "do x",
                    metadata = mapOf("a" to "1", "b" to "2"),
                    attachments = listOf(Attachment("log.txt", "line")),
                )

            assertEquals(
                """{"message":"m\n\nHow to fix: do x\n\na: 1, b: 2\n\n--- log.txt ---\nline",""" +
                    """"severity":"WARNING","code":{"value":"r"}}""" + "\n",
                body(listOf(finding)),
            )
        }

    @Test
    fun `finding explanation wins over report explanation as original output`() =
        runTest {
            val own = Finding(severity = Severity.ERROR, message = "a", rule = "r1", explanation = "own")
            val fromReport = Finding(severity = Severity.ERROR, message = "b", rule = "r2")
            val explanations = mapOf("r1" to "report r1", "r2" to "report r2")

            val lines = body(listOf(own, fromReport), explanations).lines()

            assertEquals(
                """{"message":"a","severity":"ERROR","code":{"value":"r1"},"original_output":"own"}""",
                lines[0],
            )
            assertEquals(
                """{"message":"b","severity":"ERROR","code":{"value":"r2"},"original_output":"report r2"}""",
                lines[1],
            )
        }

    @Test
    fun `a fix hint renders labelled lines and stays out of the metadata`() =
        runTest {
            val finding =
                Finding(
                    severity = Severity.ERROR,
                    message = "bad title",
                    rule = "title",
                    suggestion = "rename it",
                    metadata = mapOf(
                        "k" to "v",
                        WellKnownMetadataKeys.MISMATCH to Mismatch("feat add x", 4, "\": \"").toMetadataValue(),
                        WellKnownMetadataKeys.EXPECTED_FORMAT to "\"feat\" \": \" .+",
                    ),
                )

            val line = body(listOf(finding))

            val expected =
                """"bad title\n\nWhere: `feat add x` — after `feat` expected `\": \"` — found whitespace\n""" +
                    """How to fix: rename it\nFormat: `\"feat\" \": \" .+`\n\nk: v""""

            assertTrue(expected in line, line)
        }
}
