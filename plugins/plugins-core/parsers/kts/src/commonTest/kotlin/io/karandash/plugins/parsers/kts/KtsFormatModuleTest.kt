package io.karandash.plugins.parsers.kts

import io.karandash.api.ExperimentalKarandashApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalKarandashApi::class)
class KtsFormatModuleTest {
    private val module =
        KtsFormatModule().withRuleSchemas(
            buildJsonObject {
                putJsonObject("titleFormat") {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("pattern") { put("type", "string") } }
                }
            },
        )

    @Test
    fun `formatId is kts`() {
        assertEquals("kts", module.formatId)
    }

    @Test
    fun `parseToJsonElement returns empty JsonObject for blank content`() {
        runTest {
            assertEquals(JsonObject(emptyMap()), module.parseToJsonElement(""))
            assertEquals(JsonObject(emptyMap()), module.parseToJsonElement("   "))
        }
    }

    @Test
    fun `parseToJsonElement returns correct JsonObject for minimal valid KTS`() {
        runTest {
            val kts = """
                rules {
                    titleFormat {
                        pattern = "^feat:.*"
                    }
                }
            """.trimIndent()

            val result = module.parseToJsonElement(kts)

            assertNotNull(result)
            assertTrue(result is JsonObject)
            assertTrue(result.containsKey("rules"))
        }
    }

    @Test
    fun `parseExtendsOnly returns null without extends`() {
        runTest {
            val kts = """
                rules {
                    titleFormat {
                        pattern = "^feat:.*"
                    }
                }
            """.trimIndent()
            assertNull(module.parseExtendsOnly(kts))
        }
    }

    @Test
    fun `parseExtendsOnly returns the top-level extends value`() {
        runTest {
            val kts = """
                karandash {
                    extends = "https://example.com/base.yaml"
                    rules { titleFormat { pattern = "^feat" } }
                }
            """.trimIndent()
            assertEquals(JsonPrimitive("https://example.com/base.yaml"), module.parseExtendsOnly(kts))
        }
    }

    @Test
    fun `parseExtendsOnly reads a list of extends sources`() {
        runTest {
            val kts = "extends = listOf(\"a.yaml\", \"b.kts\")"
            assertEquals(JsonArray(listOf(JsonPrimitive("a.yaml"), JsonPrimitive("b.kts"))), module.parseExtendsOnly(kts))
        }
    }

    @Test
    fun `parseToJsonElement rejects invalid KTS instead of returning an empty config`() {
        runTest {
            assertFailsWith<IllegalArgumentException> { module.parseToJsonElement("rules { titleFormat { pattern = } }") }
            assertFailsWith<IllegalArgumentException> { module.parseToJsonElement("rules { titleFormat { pattern = \"a\" # } }") }
        }
    }

    @Test
    fun `parseExtendsOnly returns null for blank content`() {
        runTest {
            assertNull(module.parseExtendsOnly(""))
        }
    }
}
