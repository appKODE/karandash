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

/** The rdjson output carries the reviewdog diagnostic fields with valid values for every finding kind. */
class RdjsonSchemaTest {
    @Test
    fun `every diagnostic has a message a known severity a rule code and a valid location`() =
        runTest {
            val findings =
                Severity.entries.map { createTestFinding(ruleId = "rule", severity = it, message = "m \"q\" <t> \\ ${it.name}") } +
                    createTestFinding(ruleId = "other", message = "f", file = "src/A.kt", line = 7) +
                    createTestFinding(ruleId = "other", message = "g", file = "src/B.kt") +
                    createTestFinding(ruleId = "other", message = "c").copy(location = Location.CommitRef("abc"))

            val root = Json
                .parseToJsonElement(
                    RdjsonFormatModule().format(createTestReport(findings = findings), JsonObject(emptyMap())).body
                ).jsonObject

            val diagnostics = root.getValue("diagnostics").jsonArray.map { it.jsonObject }
            assertEquals(findings.size, diagnostics.size)
            diagnostics.forEach { diagnostic ->
                assertTrue(
                    diagnostic
                        .getValue("message")
                        .jsonPrimitive.content
                        .isNotBlank(),
                    "$diagnostic"
                )
                assertTrue(diagnostic.getValue("severity").jsonPrimitive.content in SEVERITIES, "$diagnostic")
                assertTrue(
                    diagnostic
                        .getValue("code")
                        .jsonObject
                        .getValue("value")
                        .jsonPrimitive.content
                        .isNotBlank(),
                    "$diagnostic"
                )
                diagnostic["location"]?.jsonObject?.let { location ->
                    assertTrue(
                        location
                            .getValue("path")
                            .jsonPrimitive.content
                            .isNotBlank(),
                        "$diagnostic"
                    )
                    location["range"]?.jsonObject?.let {
                        assertTrue(
                            it
                                .getValue("start")
                                .jsonObject
                                .getValue("line")
                                .jsonPrimitive.int >= 1,
                            "$diagnostic"
                        )
                    }
                }
            }
        }

    private companion object {
        val SEVERITIES = setOf("UNKNOWN_SEVERITY", "ERROR", "WARNING", "INFO")
    }
}
