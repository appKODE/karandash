package io.karandash.sdk.config

import io.karandash.api.support.mapToJsonElement
import io.karandash.sdk.PluginConfig
import io.karandash.sdk.typed.toFormatId
import io.karandash.sdk.typed.toModuleId
import io.karandash.sdk.typed.toProviderId
import io.karandash.sdk.typed.toRuleId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigSupportTest {
    @Serializable
    enum class Mode { FAST, SLOW }

    @Serializable
    data class Inner(
        val depth: Int = 1,
    ) : PluginConfig

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Sample(
        val name: String = "n",
        val count: Int = 0,
        val big: Long = 0,
        val ratio: Float = 0f,
        val weight: Double = 0.0,
        val on: Boolean = false,
        val tags: List<String> = emptyList(),
        val extra: Map<String, String> = emptyMap(),
        val inner: Inner = Inner(),
        val items: List<Inner> = emptyList(),
        val mode: Mode = Mode.FAST,
        @JsonNames("legacyKey") val renamed: String? = null,
        val required: String,
    ) : PluginConfig

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `schema lists every field with its type optionality and nesting`() {
        val fields = configSchemaFromDescriptor(Sample.serializer().descriptor)

        assertEquals(
            listOf(
                ConfigFieldType.STRING,
                ConfigFieldType.INT,
                ConfigFieldType.LONG,
                ConfigFieldType.FLOAT,
                ConfigFieldType.DOUBLE,
                ConfigFieldType.BOOLEAN,
                ConfigFieldType.LIST,
                ConfigFieldType.MAP,
                ConfigFieldType.OBJECT,
                ConfigFieldType.LIST,
                ConfigFieldType.ENUM,
                ConfigFieldType.STRING,
                ConfigFieldType.STRING,
            ),
            fields.map { it.type },
        )
        assertEquals(listOf("depth"), fields.single { it.name == "inner" }.nested?.map { it.name })
        assertEquals(listOf("depth"), fields.single { it.name == "items" }.nested?.map { it.name })
        assertNull(fields.single { it.name == "tags" }.nested)
        assertEquals(listOf("required"), fields.filterNot { it.optional }.map { it.name })
        assertEquals(fields, configSchema<Sample>())
    }

    @Test
    fun `fields bridge to legacy properties with object lists recognised`() {
        val properties = configFieldsToProperties(configSchemaFromDescriptor(Sample.serializer().descriptor))
            .associateBy { it.name }

        assertEquals(ConfigPropertyType.STRING_LIST, properties.getValue("tags").type)
        assertEquals(ConfigPropertyType.OBJECT_LIST, properties.getValue("items").type)
        assertEquals(ConfigPropertyType.OBJECT, properties.getValue("inner").type)
        assertEquals(ConfigPropertyType.INT, properties.getValue("big").type)
        assertTrue(properties.getValue("required").required)
        assertTrue(properties.values.filter { it.name != "required" }.none { it.required })
    }

    @Test
    fun `unknown keys are reported as dotted paths and json names count as declared`() {
        val element = json("""{"name":"x","legacyKey":"y","typo":1,"inner":{"depth":2,"deep":3},"extra":{"any":"v"},"items":[{"z":1}]}""")

        assertEquals(listOf("typo", "inner.deep"), unknownConfigKeys(Sample.serializer().descriptor, element))
    }

    @Test
    fun `unknown keys ignore non objects and free form configs`() {
        assertEquals(emptyList(), unknownConfigKeys(Sample.serializer().descriptor, JsonPrimitive(1)))
        assertEquals(emptyList(), unknownConfigKeys(EmptyConfig.serializer().descriptor, json("""{"a":1}""")))
        assertEquals(emptyList(), unknownConfigKeys(Mode.serializer().descriptor, json("""{"a":1}""")))
    }

    @Test
    fun `decoding reports the error and falls back to defaults`() {
        val ok = deserializeConfigReporting(Inner.serializer(), json("""{"depth":4}"""))
        val bad = deserializeConfigReporting(Inner.serializer(), json("""{"depth":"deep"}"""))
        val wrongShape = deserializeConfigReporting(Inner.serializer(), JsonArray(emptyList()))

        assertEquals(Inner(4) to null, ok)
        assertEquals(Inner(), bad.first)
        assertNotNull(bad.second)
        assertEquals(Inner(), wrongShape.first)
        assertNotNull(wrongShape.second)
    }

    @Test
    fun `reified helpers decode and default`() {
        assertEquals(Sample(required = "r", count = 2), deserializeConfig<Sample>(json("""{"required":"r","count":2}""")))
        assertEquals(Inner(), defaultConfig<Inner>())
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `json elements convert to plain values`() {
        val element = json("""{"s":"x","b":true,"i":1,"l":5000000000,"d":1.5,"n":null,"a":[1,"y"],"o":{"k":false}}""")

        assertEquals(
            mapOf(
                "s" to "x",
                "b" to true,
                "i" to 1,
                "l" to 5_000_000_000L,
                "d" to 1.5,
                "n" to null,
                "a" to listOf(1, "y"),
                "o" to mapOf("k" to false),
            ),
            element.toAnyMap(),
        )
        assertEquals(emptyMap(), JsonArray(emptyList()).toAnyMap())
        assertNull(JsonNull.toAnyValue())
        assertEquals("raw", JsonUnquotedLiteral("raw").toAnyValue())
    }

    @Test
    fun `plain values convert back to json`() {
        val map = mapOf("a" to 1, "b" to listOf("x"), "c" to null)
        assertEquals(json("""{"a":1,"b":["x"],"c":null}"""), mapToJsonElement(map))
        assertEquals(JsonPrimitive("s"), anyToJsonElement("s"))
        assertEquals(map, mapToJsonElement(map).toAnyMap())
    }

    @Test
    fun `config json encodes defaults and omits nulls`() {
        val encoded = Sample(count = 3, required = "r").toConfigJson().jsonObject

        assertEquals(JsonPrimitive(3), encoded["count"])
        assertEquals(JsonPrimitive("n"), encoded["name"])
        assertEquals(json("""{"depth":1}"""), encoded["inner"])
        assertNull(encoded["renamed"])
    }

    @Test
    fun `profile rule configs serialize each typed config`() {
        val configs = profileRuleConfigs {
            rule("inner", Inner(depth = 5))
            rule("plain", Inner())
        }

        assertEquals(mapOf("inner" to json("""{"depth":5}"""), "plain" to json("""{"depth":1}""")), configs)
    }

    @Test
    fun `empty config serializer reads and writes an empty object`() {
        assertEquals("{}", Json.encodeToString(EmptyConfig.serializer(), EmptyConfig))
        assertEquals(EmptyConfig, Json.decodeFromString(EmptyConfig.serializer(), "{}"))
        assertEquals(0, EmptyConfig.serializer().descriptor.elementsCount)
    }

    @Test
    fun `typed ids print their raw value`() {
        assertEquals("r", "r".toRuleId().toString())
        assertEquals("m", "m".toModuleId().toString())
        assertEquals("f", "f".toFormatId().toString())
        assertEquals("p", "p".toProviderId().toString())
        assertEquals("r".toRuleId(), "r".toRuleId())
        assertEquals("m", "m".toModuleId().value)
    }
}
