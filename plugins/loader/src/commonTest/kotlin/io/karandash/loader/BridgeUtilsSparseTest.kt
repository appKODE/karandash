package io.karandash.loader

import io.karandash.api.ModuleType
import io.karandash.api.config.ConfigPropertyType
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A guest may send any field absent or null; the bridge parsers fill each with its default. */
class BridgeUtilsSparseTest {
    private fun obj(text: String): JsonObject = KarandashJson.Default.parseToJsonElement(text).jsonObject

    @Test
    fun `an empty config property object gets every default`() {
        val property = BridgeUtils.parseConfigProperty(obj("{}"))
        assertEquals("", property.name)
        assertEquals(ConfigPropertyType.STRING, property.type)
        assertEquals("", property.description)
        assertEquals(false, property.required)
        assertNull(property.defaultValue)
        assertNull(property.enumValues)
        assertNull(property.deprecated)
    }

    @Test
    fun `an unknown property type warns and falls back to string`() {
        val warnings = mutableListOf<Exception>()
        val property = BridgeUtils.parseConfigProperty(
            obj("""{"name":"n","type":"color","required":"true","enumValues":[1,{"x":1}],"deprecated":"d"}"""),
        ) { warnings += it }
        assertEquals(ConfigPropertyType.STRING, property.type)
        assertEquals(1, warnings.size)
        assertTrue(property.required)
        assertEquals(listOf("1"), property.enumValues)
        assertEquals("d", property.deprecated)
        assertEquals(ConfigPropertyType.INT, BridgeUtils.parseConfigProperty(obj("""{"type":"int"}""")).type)
    }

    @Test
    fun `ci environment keeps json null and absent fields null`() {
        assertEquals(CIEnvironment("", null, null, null, null), BridgeUtils.parseCIEnvironment(obj("{}")))
        assertEquals(
            CIEnvironment("gl", "p", null, null, "https://h"),
            BridgeUtils.parseCIEnvironment(obj("""{"platform":"gl","projectId":"p","mrId":null,"token":null,"baseUrl":"https://h"}""")),
        )
        assertEquals(CIEnvironment("", null, null, null, null), BridgeUtils.parseCIEnvironment(obj("""{"platform":null}""")))
    }

    @Test
    fun `parsed merge request url fills absent fields with empty strings`() {
        assertEquals(ParsedMergeRequestUrl("", "", "", ""), BridgeUtils.parseParsedMergeRequestUrl(obj("{}")))
        assertEquals(
            ParsedMergeRequestUrl("gh", "https://h", "o/r", "2"),
            BridgeUtils.parseParsedMergeRequestUrl(obj("""{"platform":"gh","baseUrl":"https://h","projectId":"o/r","mrId":"2"}""")),
        )
    }

    @Test
    fun `an unknown module type names the bridge in the error`() {
        assertEquals(ModuleType.RULE, BridgeUtils.parseModuleType("rule"))
        val error = assertFailsWith<IllegalArgumentException> { BridgeUtils.parseModuleType("nope", "wasm plugin") }
        assertTrue(checkNotNull(error.message).startsWith("Unsupported wasm plugin"), error.message)
    }
}
