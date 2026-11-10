package io.karandash.plugins.formats

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SarifDefaultsTest {
    @Test
    fun `optional SARIF objects left at their defaults are omitted rather than null`() {
        val tool = KarandashJson.PrettyWithDefaults.encodeToJsonElement(SarifToolComponent(name = "karandash", version = "1")) as JsonObject
        val rule = KarandashJson.PrettyWithDefaults.encodeToJsonElement(
            SarifRule(id = "r", shortDescription = SarifMessage("s"))
        ) as JsonObject
        val result =
            KarandashJson.PrettyWithDefaults.encodeToJsonElement(
                SarifResult(ruleId = "r", level = "note", message = SarifMessage("m")),
            ) as JsonObject
        val physical =
            KarandashJson.PrettyWithDefaults.encodeToJsonElement(SarifPhysicalLocation(SarifArtifactLocation("a.kt"))) as JsonObject

        assertFalse("informationUri" in tool)
        assertEquals("[]", tool["rules"].toString())
        assertFalse("fullDescription" in rule)
        assertFalse("properties" in result)
        assertFalse("region" in physical)
        assertEquals(
            "a.kt",
            physical
                .getValue("artifactLocation")
                .jsonObject
                .getValue("uri")
                .toString()
                .trim('"')
        )
    }

    @Test
    fun `description names the SARIF version`() {
        assertTrue("SARIF 2.1.0" in SarifFormatModule().description)
    }
}
