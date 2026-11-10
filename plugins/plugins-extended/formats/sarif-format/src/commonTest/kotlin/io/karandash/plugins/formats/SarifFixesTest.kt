package io.karandash.plugins.formats

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestFinding
import io.karandash.testfixtures.createTestReport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SarifFixesTest {
    private suspend fun results(vararg findings: Finding) =
        Json
            .parseToJsonElement(SarifFormatModule().format(createTestReport(findings = findings.toList()), JsonObject(emptyMap())).body)
            .jsonObject
            .getValue("runs")
            .jsonArray
            .first()
            .jsonObject
            .getValue("results")
            .jsonArray
            .map { it.jsonObject }

    @Test
    fun `a finding with a suggested code change carries a sarif fix with the deleted region`() =
        runTest {
            val finding =
                createTestFinding(
                    ruleId = "codeQuality",
                    severity = Severity.WARNING,
                    message = "use constants",
                    suggestion = "inline the value",
                    metadata =
                        mapOf(
                            WellKnownMetadataKeys.SUGGESTED_CODE to "val x = 1\nval y = 2\n",
                            WellKnownMetadataKeys.EXISTING_CODE to "val x = 0\nval y = 0\n",
                        ),
                ).copy(location = Location.FileRef("src/A.kt", 4))

            val fix =
                results(finding)
                    .single()
                    .getValue("fixes")
                    .jsonArray
                    .single()
                    .jsonObject
            val change = fix
                .getValue("artifactChanges")
                .jsonArray
                .single()
                .jsonObject
            val replacement = change
                .getValue("replacements")
                .jsonArray
                .single()
                .jsonObject
            val region = replacement.getValue("deletedRegion").jsonObject

            assertEquals(
                "inline the value",
                fix
                    .getValue("description")
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            )
            assertEquals(
                "src/A.kt",
                change
                    .getValue("artifactLocation")
                    .jsonObject
                    .getValue("uri")
                    .jsonPrimitive.content
            )
            assertEquals("4", region.getValue("startLine").jsonPrimitive.content)
            assertEquals("5", region.getValue("endLine").jsonPrimitive.content)
            assertEquals(
                "val x = 1\nval y = 2\n",
                replacement
                    .getValue("insertedContent")
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `a finding without a code change has no fixes`() =
        runTest {
            assertNull(results(createTestFinding(ruleId = "title", message = "t", file = "a.kt", line = 1)).single()["fixes"])
        }
}
