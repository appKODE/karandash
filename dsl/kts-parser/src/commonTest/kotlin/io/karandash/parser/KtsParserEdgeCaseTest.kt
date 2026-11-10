package io.karandash.parser

import io.karandash.api.exception.ConfigParseException
import io.karandash.config.KarandashLimits
import io.karandash.parser.ast.Assignment
import io.karandash.parser.ast.ConfigAst
import io.karandash.parser.ast.Expression
import io.karandash.parser.ast.FieldAccessExpr
import io.karandash.parser.ast.ListExpr
import io.karandash.parser.ast.LiteralExpr
import io.karandash.parser.ast.LogicalExpr
import io.karandash.parser.ast.NamedArgExpr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KtsParserEdgeCaseTest {
    private fun kts(input: String): JsonElement = KarandashParser().parseToJson(input)

    private fun json(input: String): JsonElement = Json.parseToJsonElement(input)

    private fun valueOf(expression: String): JsonElement? =
        (kts("a { v = $expression }") as JsonObject)["a"]?.let { (it as JsonObject)["v"] }

    @Test
    fun `an unclosed string literal carries the UNCLOSED_STRING_LITERAL code`() {
        val unclosed = assertFailsWith<ConfigParseException> { kts("a { v = \"abc }") }
        assertTrue(unclosed.message.orEmpty().startsWith("[E0001] Syntax errors in configuration: "), unclosed.message)
        val other = assertFailsWith<ConfigParseException> { kts("a { v = }") }
        assertTrue(other.message.orEmpty().startsWith("Syntax errors in configuration: "), other.message)
    }

    @Test
    fun `value expressions map to JSON`() {
        val unset = "KARANDASH_TEST_SURELY_UNSET"
        mapOf(
            "\"s\"" to "\"s\"",
            "42" to "42",
            "-3" to "-3",
            "1.25" to "1.25",
            "true" to "true",
            "listOf()" to "[]",
            "listOf(1, \"a\", listOf(true),)" to "[1, \"a\", [true]]",
            "env.FOO" to "\"\${FOO}\"",
            "env(\"FOO\")" to "\"\${FOO}\"",
            "1 == 1" to "true",
            "1 != 1" to "false",
            "2 > 1" to "false",
            "\"a\" matches \"a\"" to "false",
            "!false" to "true",
            "true && false" to "false",
            "false || true" to "true",
            "(true)" to "true",
            "env.$unset == \"null\"" to "true",
            "env(\"$unset\") != \"null\"" to "false",
            "Obj(k = 1, m = \"x\")" to "{\"k\": 1, \"m\": \"x\"}",
            "\"tab\\tcr\\rq\\q\"" to "\"tab\\tcr\\rqq\"",
        ).forEach { (expression, expected) -> assertEquals(json(expected), valueOf(expression), expression) }
    }

    @Test
    fun `unsupported values and conditions are rejected`() {
        listOf(
            "a { v = mr.title }",
            "a { v = env }",
            "a { v = foo() }",
            "a { v = env(\"A\", \"B\") }",
            "a { v = f(1, k = 2) }",
            "a { v = env(1 == 1) }",
            "a { v = env(listOf()) }",
            "a { v = mr.title == \"x\" }",
            "a { v = listOf(1) == 1 }",
            "a { v = foo(1) == 1 }",
            "a { v = @ }",
            "a { if (\"yes\") { b() } }",
            "a { if (1) { b() } }",
            "a { if (env.X) { b() } }",
            "a { if (foo(\"x\")) { b() } }",
            "a { if (env(\"A\", \"B\")) { b() } }",
            "a { if (listOf()) { b() } }",
            "a { if (Obj(k = 1)) { b() } }",
            "a { b(1, k = 2) }",
            "a { b(k = \"x\") { } }",
            "policies { r = 1 }",
        ).forEach { input -> assertFailsWith<ConfigParseException>(input) { kts(input) } }
    }

    @Test
    fun `statement calls blocks and branches take their YAML shapes`() {
        mapOf(
            "a { b(1, 2) }" to """{"a": {"b": [1, 2]}}""",
            "a { b(k = 1) }" to """{"a": {"b": {"k": 1}}}""",
            "a { b(\"v\") }" to """{"a": {"b": "v"}}""",
            "a { b(env.X) { y = 1 }; c(5) { } }" to """{"a": {"${'$'}{X}": {"y": 1}, "5": {}}}""",
            "a { b = 1 }\na { b { c = 1 } }" to """{"a": {"b": {"c": 1}}}""",
            "a { if (false) { b = 1 } }" to """{"a": {}}""",
            "a { if (\"true\") { b = 1 } }" to """{"a": {"b": 1}}""",
            "x = 3\nif (false) { x = 1 } else { y = 2 }" to """{"x": 3, "y": 2}""",
            "if (false) { x = 1 }" to "{}",
            "if (true) { x(1); rules { t { p = 1 } } }" to """{"x": 1, "rules": {"t": {"p": 1}}}""",
            "if (false) { x = 1 } else if (false) { x = 2 } else { x = 3 }" to """{"x": 3}""",
            "a { /* note */ b = 1 }" to """{"a": {"b": 1}}""",
        ).forEach { (input, expected) -> assertEquals(json(expected), kts(input), input) }
    }

    @Test
    fun `policies scripts and profile cover every entry form`() {
        mapOf(
            "policies { if (true) { p { a = 1 } }; q(\"v\") }" to """{"policies": [{"a": 1}, "v"]}""",
            "policies { a { x = 1 } }\npolicies { b { x = 2 } }" to """{"policies": [{"x": 1}, {"x": 2}]}""",
            "scripts { s() }" to """{"scripts": [{}]}""",
            "profile { id = \"p\" }" to """{"profile": {"id": "p"}}""",
            "profile { overrides { ai = 1 }; rules { t { m = 1 } } }" to
                """{"profile": {"overrides": {"ai": 1, "rules": {"t": {"m": 1}}}}}""",
        ).forEach { (input, expected) -> assertEquals(json(expected), kts(input), input) }
    }

    @Test
    fun `env defaults are read from wrappers and only from literal assignments`() {
        val unset = "KARANDASH_TEST_SURELY_UNSET"
        val actual = kts(
            """
            karandash {
                env { $unset = "w"; OTHER = env.X }
                options { env { K2_UNSET_TOO = 1 } }
                env("ignored") { }
                if (env.$unset == "w" && env.K2_UNSET_TOO == "1") { hit = true }
                if (env.OTHER == "null") { otherUnset = true }
            }
            """.trimIndent(),
        )
        val expected = json(
            """{"env": {"$unset": "w", "OTHER": "${'$'}{X}", "K2_UNSET_TOO": 1}, "ignored": {}, "hit": true, "otherUnset": true}""",
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `required rule fields come from the rule schema`() {
        val schema = RuleSchema.fromJsonSchema(
            json("""{"properties": {"pattern": {"type": "string"}}, "required": ["pattern"]}""") as JsonObject,
        )
        val parser = KarandashParser(mapOf("titleFormat" to schema))

        assertEquals(
            json("""{"rules": {"titleFormat": {"pattern": "x"}, "other": {}}}"""),
            parser.parseToJson("rules { titleFormat { pattern = \"x\" }; other() }"),
        )
        listOf("rules { titleFormat { maxLength = 1 } }", "rules { titleFormat = true }").forEach { input ->
            val error = assertFailsWith<ConfigParseException>(input) { parser.parseToJson(input) }
            assertEquals("pattern required", error.message)
        }
    }

    @Test
    fun `json schema properties map to field types defaults and required names`() {
        val schema = RuleSchema.fromJsonSchema(
            json(
                """
                {"properties": {
                  "s": {"type": "string", "default": "x"}, "i": {"type": "integer"}, "b": {"type": "boolean", "default": true},
                  "l": {"type": "array", "items": {"type": "string"}}, "o": {"type": "object", "default": {}},
                  "lo": {"type": "array", "items": {"type": "object"}}, "la": {"type": "array"}, "n": {"type": 5},
                  "nt": {}, "x": "not an object"},
                 "required": ["s", "o", "missing", 3, {}, null]}
                """,
            ) as JsonObject,
        )

        assertEquals(
            mapOf("s" to FieldType.STRING, "i" to FieldType.INT, "b" to FieldType.BOOLEAN, "l" to FieldType.STRING_LIST),
            schema.fields,
        )
        assertEquals(mapOf("s" to "s required"), schema.required)
        assertEquals(mapOf<String, Any>("s" to JsonPrimitive("x"), "b" to JsonPrimitive(true)), schema.defaults)
        listOf("{}", """{"properties": [], "required": "s"}""").forEach { input ->
            assertEquals(RuleSchema(emptyMap()), RuleSchema.fromJsonSchema(json(input) as JsonObject), input)
        }
    }

    @Test
    fun `evaluator rejects expressions the parser never produces`() {
        val evaluator = ExpressionEvaluator(emptyMap())

        assertEquals("q", evaluator.evalString(LiteralExpr("\"q\"")))
        assertEquals("\"q", evaluator.evalString(LiteralExpr("\"q")))
        assertTrue(evaluator.evalBoolean(LiteralExpr("true")))
        assertFailsWith<ConfigParseException> { evaluator.evalBoolean(FieldAccessExpr(listOf("x"))) }
        assertFailsWith<ConfigParseException> { evaluator.evalString(ListExpr(emptyList())) }
        assertFailsWith<ConfigParseException> {
            evaluator.evalCondition(LogicalExpr(LiteralExpr(true), "xor", LiteralExpr(false)))
        }
        val namedOutsideCall = ConfigAst(emptyList(), listOf(Assignment("a", NamedArgExpr("k", LiteralExpr(1)))))
        assertFailsWith<ConfigParseException> { KtsJsonInterpreter(emptyMap(), namedOutsideCall).interpret() }
    }

    @Test
    fun `a KTS if condition runs on the core conditions engine and its nesting limit`() {
        val evaluator = ExpressionEvaluator(emptyMap())

        fun nested(depth: Int): Expression =
            (1..depth).fold(LiteralExpr(true) as Expression) { inner, _ ->
                LogicalExpr(LogicalExpr(LiteralExpr(false), "||", inner), "&&", LiteralExpr(true))
            }

        assertTrue(evaluator.evalCondition(nested(KarandashLimits.MAX_NESTING_DEPTH)))
        assertFailsWith<ConfigParseException> { evaluator.evalCondition(nested(KarandashLimits.MAX_NESTING_DEPTH + 1)) }
    }
}
