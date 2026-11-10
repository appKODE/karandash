package io.karandash.plugins.parsers.kts

import io.karandash.api.ExperimentalKarandashApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(ExperimentalKarandashApi::class)
class KtsFormatModuleRuleSchemasTest {
    private val schemas = buildJsonObject {
        putJsonObject("titleFormat") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("pattern") { put("type", "string") }
                putJsonObject("maxLength") {
                    put("type", "integer")
                    put("default", 72)
                }
                putJsonObject("nested") { put("type", "object") }
            }
            putJsonArray("required") { add("pattern") }
        }
    }

    private val kts = """
        rules {
            titleFormat {
                pattern = "^feat:.*"
            }
        }
    """.trimIndent()

    private suspend fun titleFormat(module: KtsFormatModule): JsonObject {
        val root = module.parseToJsonElement(kts).jsonObject
        return root
            .getValue("rules")
            .jsonObject
            .getValue("titleFormat")
            .jsonObject
    }

    @Test
    fun `withRuleSchemas returns a configured copy and leaves the original untouched`() =
        runTest {
            val original = KtsFormatModule()

            val configured = original.withRuleSchemas(schemas) as KtsFormatModule

            assertNotSame(original, configured)
            val rule = titleFormat(configured)
            assertEquals(JsonPrimitive("^feat:.*"), rule["pattern"])
            // Schema defaults are not injected: the KTS tree matches YAML, defaults come from the rule module.
            assertTrue("maxLength" !in rule)
            assertEquals(rule, titleFormat(original))
            // The unconfigured original has no schemas, so it does not enforce the required `pattern`.
            original.parseToJsonElement("rules {\n    titleFormat {\n        maxLength = 10\n    }\n}")
        }

    @Test
    fun `withRuleSchemas enforces required fields`() =
        runTest {
            val configured = KtsFormatModule().withRuleSchemas(schemas)

            assertFailsWith<IllegalArgumentException> {
                configured.parseToJsonElement("rules {\n    titleFormat {\n        maxLength = 10\n    }\n}")
            }
        }
}
