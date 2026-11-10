package io.karandash.plugins.parsers.yaml

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YamlFormatModuleTest {
    private val module = YamlFormatModule()

    @Test
    fun `formatId is yaml`() {
        assertEquals("yaml", module.formatId)
    }

    @Test
    fun `parseToJsonElement returns correct structure for minimal valid YAML`() {
        runTest {
            val yaml = """
                rules:
                  title-format:
                    pattern: "^feat:.*"
            """.trimIndent()

            val result = module.parseToJsonElement(yaml)

            assertNotNull(result)
            val rules = result.jsonObject["rules"]?.jsonObject
            assertNotNull(rules)
            val titleFormat = rules["title-format"]?.jsonObject
            assertNotNull(titleFormat)
            assertEquals("^feat:.*", titleFormat["pattern"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `parseToJsonElement returns empty JsonObject for blank content`() {
        runTest {
            assertEquals(JsonObject(emptyMap()), module.parseToJsonElement(""))
            assertEquals(JsonObject(emptyMap()), module.parseToJsonElement("   "))
        }
    }

    @Test
    fun `parseToJsonElement returns empty JsonObject for comment-only YAML`() {
        runTest {
            val yaml = """
                # This is a comment
                # Another comment
            """.trimIndent()
            assertEquals(JsonObject(emptyMap()), module.parseToJsonElement(yaml))
        }
    }

    @Test
    fun `parseToJsonElement coerces booleans`() {
        runTest {
            val yaml = "enabled: true"
            val result = module.parseToJsonElement(yaml)
            assertEquals(true, result.jsonObject["enabled"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun `parseToJsonElement coerces integers`() {
        runTest {
            val yaml = "count: 42"
            val result = module.parseToJsonElement(yaml)
            assertEquals(42, result.jsonObject["count"]?.jsonPrimitive?.int)
        }
    }

    @Test
    fun `parseToJsonElement coerces null literal`() {
        runTest {
            val yaml = "value: null"
            val result = module.parseToJsonElement(yaml)
            assertEquals(JsonNull, result.jsonObject["value"])
        }
    }

    @Test
    fun `parseToJsonElement throws IllegalArgumentException for invalid YAML`() {
        runTest {
            val yaml = """
                rules:
                  title-format:
                    pattern: "unclosed string
            """.trimIndent()
            assertFailsWith<IllegalArgumentException> {
                module.parseToJsonElement(yaml)
            }
        }
    }

    @Test
    fun `invalid YAML syntax carries the INVALID_YAML_SYNTAX code`() {
        runTest {
            val error = assertFailsWith<IllegalArgumentException> { module.parseToJsonElement("rules:\n  a: \"unclosed") }
            assertTrue(error.message.orEmpty().startsWith("[E0006] Invalid YAML syntax: "), error.message)
        }
    }

    @Test
    fun `parseToJsonElement throws IllegalArgumentException for list-root YAML`() {
        runTest {
            val yaml = """
                - item1
                - item2
            """.trimIndent()
            assertFailsWith<IllegalArgumentException> {
                module.parseToJsonElement(yaml)
            }
        }
    }

    @Test
    fun `parseToJsonElement throws for a scalar root such as an HTML page`() {
        runTest {
            val html = "<!DOCTYPE html>\n<html><body>Sign in</body></html>"
            assertFailsWith<IllegalArgumentException> { module.parseToJsonElement(html) }
            assertEquals(JsonObject(emptyMap()), module.parseToJsonElement("~"))
        }
    }

    /** A quoted numeric scalar stays a string; an unquoted one is still a number. */
    @Test
    fun `quoted numeric scalars stay strings`() {
        runTest {
            val yaml = """
                patterns:
                  ticket: "007"
                  flag: 'true'
                  list:
                    - "12"
                  plain: 7
                  text: "abc" # trailing comment
            """.trimIndent()

            val patterns = module
                .parseToJsonElement(yaml)
                .jsonObject
                .getValue("patterns")
                .jsonObject

            assertEquals(JsonPrimitive("007"), patterns.getValue("ticket"))
            assertEquals(JsonPrimitive("true"), patterns.getValue("flag"))
            assertEquals(JsonArray(listOf(JsonPrimitive("12"))), patterns.getValue("list"))
            assertEquals(JsonPrimitive(7), patterns.getValue("plain"))
            assertEquals(JsonPrimitive("abc"), patterns.getValue("text"))
        }
    }

    /** Quoted numbers inside a flow list or flow map stay strings too. */
    @Test
    fun `quoted numbers in flow collections stay strings`() {
        runTest {
            val yaml = """
                patterns:
                  list: ["1", '2', 3]
                  map: {a: "1", b: 2}
            """.trimIndent()

            val patterns = module
                .parseToJsonElement(yaml)
                .jsonObject
                .getValue("patterns")
                .jsonObject

            assertEquals(
                JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("2"), JsonPrimitive(3))),
                patterns.getValue("list"),
            )
            assertEquals(
                JsonObject(mapOf("a" to JsonPrimitive("1"), "b" to JsonPrimitive(2))),
                patterns.getValue("map"),
            )
        }
    }
}
