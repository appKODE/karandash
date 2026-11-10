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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SarifResultsTest {
    @Test
    fun `results carry suggestion attachments metadata and every location kind`() =
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
            val bare = createTestFinding(ruleId = "title", message = "t")
            val report = createTestReport(findings = listOf(file, commit, bare)).copy(explanations = mapOf("size" to "Keep MRs small"))

            val body =
                SarifFormatModule()
                    .format(report, JsonObject(mapOf("version" to JsonPrimitive("9.9"))))
                    .body
            val run = Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("runs")
                .jsonArray
                .single()
                .jsonObject
            val driver = run
                .getValue("tool")
                .jsonObject
                .getValue("driver")
                .jsonObject

            assertEquals("\"9.9\"", driver.getValue("version").toString())
            assertEquals(
                """[{"id":"size","shortDescription":{"text":"Rule: size"},"fullDescription":{"text":"Keep MRs small"}},""" +
                    """{"id":"title","shortDescription":{"text":"Rule: title"},"fullDescription":{"text":"Validation rule: title"}}]""",
                driver.getValue("rules").toString(),
            )
            // SARIF 2.1.0 types region and properties as objects: absent ones are omitted, never null.
            assertEquals(
                listOf(
                    """{"ruleId":"size","level":"error","message":{"text":"big\n\nHow to fix: split\n\n--- d.txt ---\n+a"},""" +
                        """"locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/A.kt"},"region":{"startLine":4}}}],"properties":{"k":"v"}}""",
                    """{"ruleId":"size","level":"note","message":{"text":"c"},"locations":[{"physicalLocation":{"artifactLocation":{"uri":"commit:abc"}}}]}""",
                    """{"ruleId":"title","level":"warning","message":{"text":"t"},"locations":[]}""",
                ),
                run.getValue("results").jsonArray.map { it.toString() },
            )
        }

    @Test
    fun `a fix hint renders labelled lines in the message`() =
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

            val body = SarifFormatModule().format(createTestReport(findings = listOf(finding)), JsonObject(emptyMap())).body

            val expected =
                """"bad title\n\nWhere: `feat add x` — after `feat` expected `\": \"` — found whitespace\n""" +
                    """How to fix: rename it\nFormat: `\"feat\" \": \" .+`""""

            assertTrue(expected in body, body)
        }

    @Test
    fun `findings sharing a fingerprint get distinct occurrence suffixed values`() =
        runTest {
            val a = createTestFinding(ruleId = "r", message = "one").copy(fingerprint = "fp")
            val b = createTestFinding(ruleId = "r", message = "two").copy(fingerprint = "fp")
            val c = createTestFinding(ruleId = "r", message = "three").copy(fingerprint = "solo")
            val body = SarifFormatModule().format(createTestReport(findings = listOf(a, b, c)), JsonObject(emptyMap())).body
            val results = Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("runs")
                .jsonArray
                .single()
                .jsonObject
                .getValue("results")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("partialFingerprints")
                        .jsonObject
                        .getValue("karandashFingerprint/v1")
                        .jsonPrimitive.content
                }

            assertEquals(listOf("fp", "fp:2", "solo"), results)
        }

    @Test
    fun `a finding error code is a result property and links the catalog docs from its rule`() =
        runTest {
            val coded = createTestFinding(ruleId = "regex", message = "bad", metadata = mapOf("k" to "v")).copy(errorCode = "E0003")
            val custom = createTestFinding(ruleId = "other", message = "o").copy(errorCode = "SEC-001")
            val plain = createTestFinding(ruleId = "plain", message = "p")
            val body = SarifFormatModule().format(createTestReport(findings = listOf(coded, custom, plain)), JsonObject(emptyMap())).body
            val run = Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("runs")
                .jsonArray
                .single()
                .jsonObject
            val rules = run
                .getValue("tool")
                .jsonObject
                .getValue("driver")
                .jsonObject
                .getValue("rules")
                .jsonArray
                .map { it.jsonObject }
            val results = run.getValue("results").jsonArray.map { it.jsonObject }

            assertEquals("""{"k":"v","errorCode":"E0003"}""", results[0].getValue("properties").toString())
            assertEquals("""{"errorCode":"SEC-001"}""", results[1].getValue("properties").toString())
            assertTrue("properties" !in results[2])
            assertTrue(rules[0].getValue("helpUri").toString().contains("error-codes/#e0003"), rules[0].toString())
            assertTrue("helpUri" !in rules[1] && "helpUri" !in rules[2])
        }
}
