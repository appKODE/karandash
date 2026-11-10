package io.karandash.loader

import io.karandash.api.ModuleType
import io.karandash.api.config.ConfigProperty
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

/** Guest JSON reaching the host through the bridges: missing, null, mistyped and malformed fields. */
class BridgeParsingTest {
    private fun obj(json: String): JsonObject = KarandashJson.Default.parseToJsonElement(json).jsonObject

    @Test
    fun `ci environment encoded by a jar guest keeps its null fields null`() {
        val wire = KarandashJson.Default.encodeToString(CIEnvironment.serializer(), CIEnvironment("gitlab", "7", null, null, null))

        val parsed = checkNotNull(BridgeDeserializer.parseCIEnvironmentFromJson(wire))

        assertEquals(
            listOf("gitlab", "7", null, null, null),
            listOf(parsed.platform, parsed.projectId, parsed.mrId, parsed.token, parsed.baseUrl)
        )
        assertNull(BridgeDeserializer.parseCIEnvironmentFromJson("""{"platform":null}"""))
    }

    @Test
    fun `ci environment needs an object with a platform`() {
        assertNull(BridgeDeserializer.parseCIEnvironmentFromJson("[]"))
        assertNull(BridgeDeserializer.parseCIEnvironmentFromJson("""{"platform":""}"""))
        assertNull(BridgeDeserializer.parseCIEnvironmentFromJson("""{"mrId":"1"}"""))
        assertNull(BridgeDeserializer.parseCIEnvironmentFromJson("not json"))
        assertEquals(CIEnvironment("gh", null, null, null, null), BridgeUtils.parseCIEnvironment(obj("""{"platform":"gh"}""")))
    }

    @Test
    fun `parsed merge request url needs all four fields`() {
        val full = """{"platform":"gh","baseUrl":"https://h","projectId":"o/r","mrId":"3"}"""
        assertEquals(ParsedMergeRequestUrl("gh", "https://h", "o/r", "3"), BridgeDeserializer.parseParsedMergeRequestUrlFromJson(full))
        listOf("platform", "baseUrl", "projectId", "mrId").forEach { key ->
            val missing = obj(full).filterKeys { it != key }.let(::JsonObject).toString()
            assertNull(BridgeDeserializer.parseParsedMergeRequestUrlFromJson(missing), key)
        }
        assertNull(BridgeDeserializer.parseParsedMergeRequestUrlFromJson("1"))
        assertEquals(ParsedMergeRequestUrl("", "", "", ""), BridgeUtils.parseParsedMergeRequestUrl(obj("{}")))
    }

    @Test
    fun `config property defaults missing fields and falls back to string for unknown types`() {
        val warnings = mutableListOf<Exception>()

        val bare = BridgeUtils.parseConfigProperty(obj("{}"), warnings::add)
        val unknown = BridgeUtils.parseConfigProperty(obj("""{"name":"x","type":"matrix"}"""), warnings::add)

        assertEquals(ConfigProperty(name = "", type = ConfigPropertyType.STRING, description = ""), bare)
        assertEquals(ConfigPropertyType.STRING, unknown.type)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `config property reads every field a guest sends`() {
        val json =
            """{"name":"level","type":"int","description":"d","required":"true","defaultValue":3,""" +
                """"enumValues":["1",2,{"x":1}],"deprecated":"use tier"}"""

        val property = BridgeUtils.parseConfigProperty(obj(json))

        assertEquals(ConfigPropertyType.INT, property.type)
        assertTrue(property.required)
        assertEquals(listOf("1", "2"), property.enumValues)
        assertEquals("use tier", property.deprecated)
        assertEquals(3, (property.defaultValue as Number).toInt())
    }

    @Test
    fun `config property list skips non-object elements and tolerates bad input`() {
        assertEquals(listOf("a"), BridgeDeserializer.parseConfigProperties("""[{"name":"a"}, 1, "s"]""").map { it.name })
        assertEquals(emptyList(), BridgeDeserializer.parseConfigProperties("""{"name":"a"}"""))
        assertEquals(emptyList(), BridgeDeserializer.parseConfigProperties("[{"))
    }

    @Test
    fun `module type trims its input and names the bridge when unknown`() {
        assertEquals(ModuleType.PLATFORM_SERVICE, BridgeUtils.parseModuleType(" platform_service "))
        val error = assertFailsWith<IllegalArgumentException> { BridgeUtils.parseModuleType("gadget", "WASM") }
        assertTrue(error.message.orEmpty().startsWith("Unsupported WASM "), error.message)
    }

    @Test
    fun `ai completion response accepts numeric token counts only`() {
        val parsed =
            BridgeDeserializer.parseAiCompletionResponse(
                """{"content":"hi","promptTokens":3.0,"completionTokens":"4","totalTokens":7,"model":5}""",
            )

        assertEquals("hi", parsed.content)
        assertEquals(listOf(3, 0, 7), listOf(parsed.promptTokens, parsed.completionTokens, parsed.totalTokens))
        assertEquals("", parsed.model)
        assertEquals("", BridgeDeserializer.parseAiCompletionResponse("[]").content)
    }

    @Test
    fun `maps keep object entries and drop what they cannot coerce`() {
        assertEquals(
            mapOf("a" to "1", "b" to "", "c" to """{"x":1}"""),
            BridgeDeserializer.parseStringMap("""{"a":1,"b":null,"c":{"x":1}}"""),
        )
        assertEquals(emptyMap(), BridgeDeserializer.parseStringMap("[]"))
        assertEquals(mapOf("a" to 2), BridgeDeserializer.parseIntMap("""{"a":2.9,"b":"3","c":null}"""))
        assertEquals(emptyMap(), BridgeDeserializer.parseIntMap("x"))
        assertEquals(setOf("k"), BridgeDeserializer.parseJsonElementMap("""{"k":[1]}""").keys)
        assertEquals(emptyMap(), BridgeDeserializer.parseJsonElementMap("[1]"))
        assertEquals(emptyMap(), BridgeDeserializer.parseJsonElementMap("{"))
    }

    @Test
    fun `command specs skip unnamed or non-object entries and recurse into subcommands`() {
        val specs =
            BridgeDeserializer.parsePluginCommandSpecs(
                """[{"name":"top","subcommands":[{"name":"sub","help":"h"},{"help":"no name"}]},{"help":"x"},3]""",
            )

        assertEquals(listOf("top"), specs.map { it.name })
        assertEquals("", specs.single().help)
        assertEquals(listOf("sub" to "h"), specs.single().subcommands.map { it.name to it.help })
        assertEquals(emptyList(), BridgeDeserializer.parsePluginCommandSpecs("{}"))
    }
}
