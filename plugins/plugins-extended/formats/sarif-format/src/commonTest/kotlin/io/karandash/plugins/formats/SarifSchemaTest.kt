package io.karandash.plugins.formats

import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The SARIF output satisfies the required shape of the SARIF 2.1.0 schema for every finding kind. */
class SarifSchemaTest {
    @Test
    fun `output has the required SARIF 2_1_0 structure for every severity and location kind`() =
        runTest {
            val findings =
                Severity.entries.map {
                    createTestFinding(
                        ruleId = "rule-${it.name}",
                        severity = it,
                        message = "m \"quoted\" <tag> \\ ${it.name}"
                    )
                } +
                    createTestFinding(ruleId = "with-file", message = "f", file = "src/A.kt", line = 7) +
                    createTestFinding(ruleId = "with-file", message = "g", file = "src/B.kt") +
                    createTestFinding(ruleId = "commit", message = "c").copy(location = Location.CommitRef("abc123"))

            val root = Json
                .parseToJsonElement(
                    SarifFormatModule().format(createTestReport(findings = findings), JsonObject(emptyMap())).body
                ).jsonObject

            assertEquals("2.1.0", root.getValue("version").jsonPrimitive.content)
            assertTrue(
                root
                    .getValue("\$schema")
                    .jsonPrimitive.content
                    .endsWith("sarif-2.1.0.json"),
                "$root"
            )
            val run = root
                .getValue("runs")
                .jsonArray
                .single()
                .jsonObject
            val driver = run
                .getValue("tool")
                .jsonObject
                .getValue("driver")
                .jsonObject
            assertTrue(
                driver
                    .getValue("name")
                    .jsonPrimitive.content
                    .isNotBlank(),
                "$driver"
            )
            val ruleIds = driver.getValue("rules").jsonArray.map {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            }
            assertEquals(ruleIds.size, ruleIds.toSet().size, "duplicate rule ids: $ruleIds")

            val results = run.getValue("results").jsonArray.map { it.jsonObject }
            assertEquals(findings.size, results.size)
            results.forEach { result ->
                assertTrue(result.getValue("ruleId").jsonPrimitive.content in ruleIds, "result rule missing from driver.rules: $result")
                assertTrue(result.getValue("level").jsonPrimitive.content in LEVELS, "bad level: $result")
                assertTrue(
                    result
                        .getValue("message")
                        .jsonObject
                        .getValue("text")
                        .jsonPrimitive.content
                        .isNotBlank(),
                    "$result"
                )
                result.getValue("locations").jsonArray.forEach { location ->
                    val physical = location.jsonObject.getValue("physicalLocation").jsonObject
                    assertTrue(
                        physical
                            .getValue("artifactLocation")
                            .jsonObject
                            .getValue("uri")
                            .jsonPrimitive.content
                            .isNotBlank(),
                        "$physical"
                    )
                    physical["region"]?.jsonObject?.let { assertTrue(it.getValue("startLine").jsonPrimitive.int >= 1, "$physical") }
                }
            }
        }

    private companion object {
        val LEVELS = setOf("none", "note", "warning", "error")
    }
}
