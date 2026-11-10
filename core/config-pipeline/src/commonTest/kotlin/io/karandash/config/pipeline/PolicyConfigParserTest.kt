package io.karandash.config.pipeline

import io.karandash.api.exception.ConfigParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyConfigParserTest {
    private val parser = PolicyConfigParser()

    @Test
    fun `absent policies key returns empty list`() {
        val config = JsonObject(mapOf("rules" to JsonObject(emptyMap())))
        val result = parser.parse(config)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty policies array returns empty list`() {
        val config = JsonObject(mapOf("policies" to JsonArray(emptyList())))
        val result = parser.parse(config)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `boolean when literal is kept as its text`() {
        val policies = listOf(true, false).map {
            JsonObject(mapOf("when" to JsonPrimitive(it), "modules" to JsonArray(listOf(JsonPrimitive("titleFormat")))))
        }
        val result = parser.parse(JsonObject(mapOf("policies" to JsonArray(policies))))
        assertEquals(listOf("true", "false"), result.map { it.condition })
    }

    @Test
    fun `single policy parsed correctly`() {
        val policy = JsonObject(
            mapOf(
                "when" to JsonPrimitive("branch starts_with 'feature/'"),
                "modules" to JsonArray(listOf(JsonPrimitive("titleFormat"))),
                "enabled" to JsonPrimitive(true),
                "severity" to JsonPrimitive("warning"),
            ),
        )
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        val result = parser.parse(config)

        assertEquals(1, result.size)
        assertEquals("branch starts_with 'feature/'", result[0].condition)
        assertEquals(listOf("titleFormat"), result[0].modules)
        assertEquals(true, result[0].enabled)
        assertEquals("warning", result[0].severity)
    }

    @Test
    fun `policy with override block parsed`() {
        val overrideObj = JsonObject(
            mapOf("titleFormat" to JsonObject(mapOf("maxLength" to JsonPrimitive(50)))),
        )
        val policy = JsonObject(
            mapOf(
                "when" to JsonPrimitive("is_draft"),
                "override" to overrideObj,
            ),
        )
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        val result = parser.parse(config)

        assertEquals(1, result.size)
        assertEquals(overrideObj, result[0].override)
    }

    @Test
    fun `policy with null modules`() {
        val policy = JsonObject(
            mapOf("when" to JsonPrimitive("is_draft")),
        )
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        val result = parser.parse(config)

        assertEquals(1, result.size)
        assertNull(result[0].modules)
    }

    @Test
    fun `multiple policies parsed in order`() {
        val p1 = JsonObject(mapOf("when" to JsonPrimitive("is_draft")))
        val p2 = JsonObject(mapOf("when" to JsonPrimitive("branch starts_with 'hotfix/'")))
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(p1, p2))))
        val result = parser.parse(config)

        assertEquals(2, result.size)
        assertEquals("is_draft", result[0].condition)
        assertEquals("branch starts_with 'hotfix/'", result[1].condition)
    }

    @Test
    fun `non-array policies throws ConfigParseException`() {
        val config = JsonObject(mapOf("policies" to JsonPrimitive("invalid")))
        assertFailsWith<ConfigParseException> {
            parser.parse(config)
        }
    }

    @Test
    fun `non-object policy entry throws ConfigParseException`() {
        val config = JsonObject(
            mapOf("policies" to JsonArray(listOf(JsonPrimitive("not an object")))),
        )
        assertFailsWith<ConfigParseException> {
            parser.parse(config)
        }
    }

    @Test
    fun `missing when field throws ConfigParseException`() {
        val policy = JsonObject(mapOf("modules" to JsonArray(listOf(JsonPrimitive("titleFormat")))))
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        assertFailsWith<ConfigParseException> {
            parser.parse(config)
        }
    }

    @Test
    fun `empty when field throws ConfigParseException`() {
        val policy = JsonObject(mapOf("when" to JsonPrimitive("")))
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        assertFailsWith<ConfigParseException> {
            parser.parse(config)
        }
    }

    @Test
    fun `non-string when field throws ConfigParseException`() {
        val policy = JsonObject(mapOf("when" to JsonPrimitive(42)))
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        assertFailsWith<ConfigParseException> {
            parser.parse(config)
        }
    }

    @Test
    fun `non-object override throws ConfigParseException`() {
        val policy = JsonObject(
            mapOf(
                "when" to JsonPrimitive("is_draft"),
                "override" to JsonPrimitive("bad"),
            ),
        )
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        assertFailsWith<ConfigParseException> {
            parser.parse(config)
        }
    }

    @Test
    fun `override block kebab-case rule ids normalized to camelCase`() {
        // Rule IDs in override block come from YAML as kebab-case (e.g. commit-body:)
        // but ruleConfigs stores them as camelCase (commitBody). Parser must normalize.
        val overrideObj = JsonObject(
            mapOf("commit-body" to JsonObject(mapOf("required" to JsonPrimitive(true)))),
        )
        val policy = JsonObject(
            mapOf(
                "when" to JsonPrimitive("has_ticket(PROJ)"),
                "override" to overrideObj,
            ),
        )
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        val result = parser.parse(config)

        assertEquals(1, result.size)
        val override = result[0].override
        assertNull(override?.get("commit-body"), "kebab-case key must be normalized away")
        assertEquals(
            JsonObject(mapOf("required" to JsonPrimitive(true))),
            override?.get("commitBody"),
            "camelCase key must be present after normalization",
        )
    }

    @Test
    fun `non-array modules throws ConfigParseException`() {
        val policy = JsonObject(
            mapOf(
                "when" to JsonPrimitive("is_draft"),
                "modules" to JsonPrimitive("bad"),
            ),
        )
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        assertFailsWith<ConfigParseException> {
            parser.parse(config)
        }
    }

    @Test
    fun `modules array kebab-case ids normalized to camelCase`() {
        // Module IDs arrive as kebab-case from YAML (e.g. commit-body) but ruleConfigs
        // stores them as camelCase (commitBody). The parser must normalize the array values.
        val policy = JsonObject(
            mapOf(
                "when" to JsonPrimitive("is_draft"),
                "modules" to JsonArray(
                    listOf(
                        JsonPrimitive("commit-body"),
                        JsonPrimitive("title-format"),
                        JsonPrimitive("alreadyCamel"),
                    ),
                ),
            ),
        )
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        val result = parser.parse(config)

        assertEquals(1, result.size)
        assertEquals(
            listOf("commitBody", "titleFormat", "alreadyCamel"),
            result[0].modules,
            "kebab-case module IDs in modules array must be normalized to camelCase",
        )
    }

    @Test
    fun `override keys three levels deep normalize while user-defined pattern names keep their spelling`() {
        // Profile policies reach the parser un-normalized, so nested schema keys must be normalized here.
        val overrideObj = JsonObject(
            mapOf(
                "commit-body" to JsonObject(
                    mapOf(
                        "max_lines" to JsonPrimitive(5),
                        "line-rules" to JsonObject(mapOf("wrap_at" to JsonObject(mapOf("hard-limit" to JsonPrimitive(100))))),
                        "patterns" to JsonObject(mapOf("my-pattern" to JsonPrimitive("x"))),
                    ),
                ),
            ),
        )
        val config = JsonObject(
            mapOf("policies" to JsonArray(listOf(JsonObject(mapOf("when" to JsonPrimitive("true"), "override" to overrideObj))))),
        )

        val rule = assertNotNull(parser.parse(config)[0].override?.get("commitBody") as? JsonObject)

        assertEquals(JsonPrimitive(5), rule["maxLines"])
        val wrapAt = (rule["lineRules"] as? JsonObject)?.get("wrapAt") as? JsonObject
        assertEquals(JsonPrimitive(100), wrapAt?.get("hardLimit"))
        assertEquals(JsonPrimitive("x"), (rule["patterns"] as? JsonObject)?.get("my-pattern"))
    }

    @Test
    fun `override block inner schema keys normalized to camelCase`() {
        // Config keys inside override objects (e.g. match-mode:) must be normalized to
        // camelCase (matchMode) so they match the keys expected by rule modules.
        val overrideObj = JsonObject(
            mapOf(
                "title-format" to JsonObject(
                    mapOf(
                        "match-mode" to JsonPrimitive("prefix"),
                        "max-length" to JsonPrimitive(72),
                    ),
                ),
            ),
        )
        val policy = JsonObject(
            mapOf(
                "when" to JsonPrimitive("is_draft"),
                "override" to overrideObj,
            ),
        )
        val config = JsonObject(mapOf("policies" to JsonArray(listOf(policy))))
        val result = parser.parse(config)

        assertEquals(1, result.size)
        val override = assertNotNull(result[0].override)
        val ruleOverride = assertNotNull(override["titleFormat"] as? JsonObject)
        assertNull(ruleOverride["match-mode"], "kebab-case inner key must be normalized away")
        assertNull(ruleOverride["max-length"], "kebab-case inner key must be normalized away")
        assertEquals(
            JsonPrimitive("prefix"),
            ruleOverride["matchMode"],
            "camelCase matchMode key must be present",
        )
        assertEquals(
            JsonPrimitive(72),
            ruleOverride["maxLength"],
            "camelCase maxLength key must be present",
        )
    }

    @Test
    fun `wrong policy value types hint the expected form`() {
        fun hintFor(config: JsonObject): String? = assertFailsWith<ConfigParseException> { parser.parse(config) }.hint

        fun policy(vararg fields: Pair<String, JsonElement>) =
            JsonObject(mapOf("policies" to JsonArray(listOf(JsonObject(mapOf(*fields))))))

        assertEquals(
            "Write 'policies' as a list of entries, each with a 'when' condition.",
            hintFor(JsonObject(mapOf("policies" to JsonPrimitive("invalid")))),
        )
        assertEquals(
            "Write policies[0] as an object such as { when: ..., modules: [...] }.",
            hintFor(JsonObject(mapOf("policies" to JsonArray(listOf(JsonPrimitive("x")))))),
        )
        assertEquals("Add a 'when' condition to policies[0].", hintFor(policy("modules" to JsonArray(emptyList()))))
        assertEquals("Write policies[0].when as a quoted condition string.", hintFor(policy("when" to JsonArray(emptyList()))))
        assertEquals("Write policies[0].when as a quoted condition string.", hintFor(policy("when" to JsonPrimitive(" "))))
        assertEquals(
            "Write policies[0].override as a map of module config.",
            hintFor(policy("when" to JsonPrimitive("true"), "override" to JsonPrimitive("x"))),
        )
        assertEquals(
            "Write policies[0].modules as a list of module ids.",
            hintFor(policy("when" to JsonPrimitive("true"), "modules" to JsonPrimitive("x"))),
        )
    }
}
