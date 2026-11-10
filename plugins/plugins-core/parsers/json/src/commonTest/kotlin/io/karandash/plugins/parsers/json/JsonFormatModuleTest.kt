package io.karandash.plugins.parsers.json

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JsonFormatModuleTest {
    private val module = JsonFormatModule()

    @Test
    fun `formatId is json`() {
        assertEquals("json", module.formatId)
    }

    @Test
    fun `parseToJsonElement returns correct structure for minimal valid JSON`() =
        runTest {
            val json = """
            {
              "rules": {
                "titleFormat": {
                  "pattern": "^feat:.*"
                }
              }
            }
            """.trimIndent()

            val result = module.parseToJsonElement(json)

            assertIs<JsonObject>(result)
            val rules = result.jsonObject["rules"]
            assertIs<JsonObject>(rules)
            val titleFormat = rules.jsonObject["titleFormat"]
            assertIs<JsonObject>(titleFormat)
            assertEquals("^feat:.*", titleFormat.jsonObject["pattern"]?.jsonPrimitive?.content)
        }

    @Test
    fun `parseToJsonElement returns empty JsonObject for blank content`() =
        runTest {
            assertEquals(JsonObject(emptyMap()), module.parseToJsonElement(""))
            assertEquals(JsonObject(emptyMap()), module.parseToJsonElement("   "))
        }

    @Test
    fun `parseToJsonElement returns empty JsonObject for empty JSON object`() =
        runTest {
            assertEquals(JsonObject(emptyMap()), module.parseToJsonElement("{}"))
        }

    @Test
    fun `parseToJsonElement returns boolean primitive`() =
        runTest {
            val json = """{"enabled": true}"""
            val result = module.parseToJsonElement(json)
            assertIs<JsonObject>(result)
            assertEquals(JsonPrimitive(true), result.jsonObject["enabled"])
        }

    @Test
    fun `parseToJsonElement returns integer primitive`() =
        runTest {
            val json = """{"count": 42}"""
            val result = module.parseToJsonElement(json)
            assertIs<JsonObject>(result)
            assertEquals(
                42,
                result.jsonObject["count"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt()
            )
        }

    @Test
    fun `parseToJsonElement returns JsonArray for JSON array root`() =
        runTest {
            val json = """["item1", "item2"]"""
            val result = module.parseToJsonElement(json)
            assertIs<JsonArray>(result)
            assertEquals(2, result.size)
        }

    @Test
    fun `parseToJsonElement throws IllegalArgumentException for invalid JSON`() =
        runTest {
            val json = """{"key": "unclosed string"""
            assertFailsWith<IllegalArgumentException> {
                module.parseToJsonElement(json)
            }
        }

    @Test
    fun `parseToJsonElement accepts comments and trailing commas`() =
        runTest {
            val json = "{\n  // rules\n  \"a\": 1, /* block */\n  \"b\": [1, 2,],\n}"
            val result = module.parseToJsonElement(json)
            assertIs<JsonObject>(result)
            assertEquals(setOf("a", "b"), result.keys)
        }
}
