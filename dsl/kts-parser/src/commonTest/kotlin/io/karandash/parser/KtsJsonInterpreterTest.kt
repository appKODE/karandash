package io.karandash.parser

import io.karandash.api.exception.ConfigParseException
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtsJsonInterpreterTest {
    private fun kts(input: String): JsonElement = KarandashParser().parseToJson(input)

    private fun json(input: String): JsonElement = KarandashJson.Default.parseToJsonElement(input)

    @Test
    fun `blocks assignments and calls map to the YAML tree`() {
        val actual = kts(
            """
            rules {
                titleFormat { pattern = "^feat"; maxLength = 72 }
                draftDetection()
                labels { required = listOf("a", "b",) }
            }
            reporters { console = true; slack = false }
            autoApprove { enabled = true }
            unknownSection { x = 1 }
            """.trimIndent(),
        )
        val expected = json(
            """
            {"rules": {"titleFormat": {"pattern": "^feat", "maxLength": 72}, "draftDetection": {},
              "labels": {"required": ["a", "b"]}},
             "reporters": {"console": true, "slack": false},
             "autoApprove": {"enabled": true},
             "unknownSection": {"x": 1}}
            """,
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `karandash wrapper and options are unwrapped - extends is kept`() {
        val actual = kts(
            """
            karandash {
                extends = "base.yaml"
                options { dryRun = true; profile = "strict" }
                rules { titleFormat { pattern = "x" } }
            }
            """.trimIndent(),
        )
        assertEquals(
            json("""{"extends": "base.yaml", "dryRun": true, "profile": "strict", "rules": {"titleFormat": {"pattern": "x"}}}"""),
            actual,
        )
    }

    @Test
    fun `env references become placeholders`() {
        assertEquals(
            json("""{"platforms": {"gitlab": {"token": "${'$'}{GITLAB_TOKEN}", "url": "${'$'}{GITLAB_URL}"}}}"""),
            kts("""platforms { gitlab { token = env.GITLAB_TOKEN; url = env("GITLAB_URL") } }"""),
        )
    }

    @Test
    fun `conditions support not - and - or - else-if and read the real environment`() {
        val actual = kts(
            """
            rules {
                if (!(env.KARANDASH_TEST_SURELY_UNSET == "true") && (false || true)) { a { x = 1 } }
                if (env("PATH")) { b() } else if (true) { c() } else { d() }
            }
            """.trimIndent(),
        )
        // PATH is denylisted, so `env("PATH")` reads as unset in conditions.
        assertEquals(json("""{"rules": {"a": {"x": 1}, "c": {}}}"""), actual)
    }

    @Test
    fun `conditions fall back to the env block for unset variables`() {
        val actual = kts(
            """
            env { KARANDASH_TEST_SURELY_UNSET = "strict"; PATH = "x" }
            rules {
                if (env.KARANDASH_TEST_SURELY_UNSET == "strict") { a() } else { b() }
                if (env("KARANDASH_TEST_SURELY_UNSET")) { c() }
                if (env("PATH")) { d() }
            }
            """.trimIndent(),
        )
        // PATH stays denied even when the env block names it, as in `${'$'}{PATH}` interpolation.
        assertEquals(
            json("""{"env": {"KARANDASH_TEST_SURELY_UNSET": "strict", "PATH": "x"}, "rules": {"a": {}, "c": {}}}"""),
            actual,
        )
    }

    @Test
    fun `named arguments build objects and keyed blocks use the key`() {
        val actual = kts(
            """
            rules {
                fileChanges { filePatterns = listOf(FilePatternRule(pattern = "src/**", required = true)) }
                rule("commit-length-max") { enabled = true }
            }
            """.trimIndent(),
        )
        assertEquals(
            json(
                """{"rules": {"fileChanges": {"filePatterns": [{"pattern": "src/**", "required": true}]},
                   "commit-length-max": {"enabled": true}}}""",
            ),
            actual,
        )
    }

    @Test
    fun `policies - scripts and profile take their YAML shapes`() {
        val actual = kts(
            """
            policies { draft { when = "is_draft"; modules = listOf("titleFormat") } }
            scripts { check { timeout = 30 }; other { path = "b.js" } }
            profile { id = "strict"; rules { titleFormat { maxLength = 50 } } }
            """.trimIndent(),
        )
        assertEquals(
            json(
                """{"policies": [{"when": "is_draft", "modules": ["titleFormat"]}],
                   "scripts": [{"path": "check", "timeout": 30}, {"path": "b.js"}],
                   "profile": {"id": "strict", "overrides": {"rules": {"titleFormat": {"maxLength": 50}}}}}""",
            ),
            actual,
        )
    }

    @Test
    fun `repeated blocks merge`() {
        assertEquals(
            json("""{"ai": {"enrichment": {"a": 1, "b": 2}, "reports": {}}}"""),
            kts("ai { enrichment { a = 1 } }\nai { enrichment { b = 2 }; reports() }"),
        )
    }

    @Test
    fun `string escapes are decoded once`() {
        assertEquals(json("""{"a": {"p": "x\\n\"y\"\n"}}"""), kts("""a { p = "x\\n\"y\"\n" }"""))
    }

    @Test
    fun `invalid input throws instead of producing an empty config`() {
        listOf(
            "rules { titleFormat { pattern = } }",
            "rules { titleFormat { pattern = \"a\" # } }",
            "rules { a { x = Severity.ERROR } }",
            "rules { a { x = mapOf(1) } }",
            "rules { a(\"k\", \"v\") { } }",
        ).forEach { input -> assertFailsWith<ConfigParseException>(input) { kts(input) } }
    }
}
