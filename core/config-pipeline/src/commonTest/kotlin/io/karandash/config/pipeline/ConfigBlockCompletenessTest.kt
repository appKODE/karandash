package io.karandash.config.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.config.DryRunScope
import io.karandash.config.HttpConfig
import io.karandash.config.ScriptMode
import io.karandash.duration.KarandashDuration
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that ALL top-level config blocks supported by [io.karandash.config.Config]
 * parse correctly through [ConfigMapParser.parseFullConfig] in a single combined config.
 *
 * This is a "kitchen sink" test: every block is present simultaneously.
 * If a new block is added to Config but not handled by ConfigMapParser, this test
 * should be extended to cover it.
 */
class ConfigBlockCompletenessTest {
    private val yamlParser = YamlFormatModule()

    private suspend fun parseYaml(yaml: String) = ConfigMapParser.parseFullConfig(yamlParser.parseToJsonElement(yaml)).config

    @Test
    fun `http block parses with kebab keys and absent sub-blocks stay null`() {
        runTest {
            val config = parseYaml(
                """
                http:
                  retry:
                    max-attempts: 4
                    base-delay: "250ms"
                  pagination:
                    max-pages: 30
                """.trimIndent(),
            )
            assertEquals(4, config.http.retry?.maxAttempts)
            assertEquals(KarandashDuration.ofMillis(250), config.http.retry?.baseDelay)
            assertEquals(30, config.http.pagination?.maxPages)
            assertEquals(null, config.http.responseCache)
            assertEquals(HttpConfig(), parseYaml("rules: {}").http)
        }
    }

    @Test
    fun `all config blocks parse correctly from a single combined YAML`() {
        runTest {
            val yaml = COMBINED_YAML

            val config = parseYaml(yaml)

            // rules
            assertNotNull(config.rules.ruleConfigs["titleFormat"], "titleFormat rule missing")
            assertNotNull(config.rules.ruleConfigs["commitMessages"], "commitMessages rule missing")

            // providers
            assertNotNull(config.platforms, "providers block missing")
            assertTrue(config.platforms!!.containsKey("gitlab"), "gitlab provider missing")
            assertTrue(config.platforms!!.containsKey("github"), "github provider missing")
            assertEquals(
                "https://gitlab.example.com",
                config.platforms!!["gitlab"]
                    ?.jsonObject
                    ?.get("baseUrl")
                    ?.jsonPrimitive
                    ?.content,
                "gitlab baseUrl",
            )

            // reporters
            assertNotNull(config.reporters, "reporters block missing")
            assertTrue(config.reporters!!.containsKey("console"), "console reporter missing")
            assertTrue(config.reporters!!.containsKey("json"), "json reporter missing")
            assertTrue(config.reporters!!.containsKey("sarif"), "sarif reporter missing")

            // platforms block (includes provider, comment-poster, mutator config)
            assertTrue(config.platforms!!.containsKey("gitlab"), "gitlab platform missing")

            // profile
            assertNotNull(config.profile, "profile missing")
            assertEquals("conventional-commits", config.profile!!.id)

            // scripts
            assertNotNull(config.scripts, "scripts block missing")
            assertEquals(2, config.scripts!!.size, "script entry count")
            assertEquals(".karandash/checks.js", config.scripts!![0].path)
            assertEquals(ScriptMode.AUTO, config.scripts!![0].mode)
            assertEquals(".karandash/security.js", config.scripts!![1].path)
            assertEquals(ScriptMode.SUBPROCESS, config.scripts!![1].mode)
            assertEquals(KarandashDuration.ofSeconds(15), config.scripts!![1].timeout)

            // dangerfile
            assertEquals("Dangerfile.js", config.dangerfile, "dangerfile")
            assertEquals("findings-baseline.json", config.baseline, "baseline")

            // dry-run
            assertTrue(config.dryRun, "dryRun")
            assertEquals(DryRunScope.AI, config.dryRunScope, "dryRunScope")

            // allow-localhost
            assertTrue(config.allowLocalhost, "allowLocalhost")
            assertTrue(config.allowPrivateNetwork, "allowPrivateNetwork")

            // enrichment
            assertNotNull(config.enrichment, "enrichment block missing")
            assertTrue(config.enrichment!!.generateDescription, "enrichment generateDescription")
            assertFalse(config.enrichment!!.generateTitle, "enrichment generateTitle")
            assertTrue(config.enrichment!!.skipIfDescriptionExists, "enrichment skipIfDescriptionExists")
            assertEquals(4000, config.enrichment!!.maxTokens, "enrichment maxTokens")

            // ai
            assertNotNull(config.ai, "ai block missing")
            assertEquals(
                "claude-sonnet-4-20250514",
                config.ai!!
                    .jsonObject["model"]
                    ?.jsonPrimitive
                    ?.content
            )
            // cache
            val cache = requireNotNull(config.cache) { "cache block missing" }
            assertEquals(KarandashDuration.ofSeconds(120), cache.responses.ttl, "responses ttl")
            assertEquals(256, cache.memory.maxEntries, "memory maxEntries")
        }
    }

    @Test
    fun `empty config produces safe defaults for all blocks`() {
        runTest {
            val config = parseYaml("")

            assertTrue(config.rules.isEmpty(), "rules should be empty")
            assertTrue(config.platforms.isNullOrEmpty(), "platforms should be null/empty")
            assertTrue(config.reporters.isNullOrEmpty(), "reporters should be null/empty")
            assertTrue(config.scripts.isNullOrEmpty(), "scripts should be null/empty")
            assertTrue(config.ai == null || config.ai == JsonNull, "ai should be null/empty")

            assertEquals(null, config.profile, "profile default")
            assertEquals(null, config.dangerfile, "dangerfile default")
            assertEquals(null, config.baseline, "baseline default")
            assertEquals(null, config.enrichment, "enrichment default")
            assertFalse(config.dryRun, "dryRun default")
            assertEquals(null, config.dryRunScope, "dryRunScope default: unset (acts as ai with dry-run)")
            assertFalse(config.allowLocalhost, "allowLocalhost default")
            assertFalse(config.allowPrivateNetwork, "allowPrivateNetwork default")
            assertEquals(null, config.cache, "cache default")
        }
    }

    @Test
    fun `scripts block parses string shorthand entries`() {
        runTest {
            val yaml = """
                scripts:
                  - ".karandash/lint.js"
                  - ".karandash/security.js"
            """.trimIndent()

            val config = parseYaml(yaml)
            assertNotNull(config.scripts)
            assertEquals(2, config.scripts!!.size)
            assertEquals(".karandash/lint.js", config.scripts!![0].path)
            assertEquals(ScriptMode.AUTO, config.scripts!![0].mode)
            assertEquals(".karandash/security.js", config.scripts!![1].path)
        }
    }

    @Test
    fun `scripts env is kept as declared and absent env stays undeclared`() {
        runTest {
            val yaml = """
                scripts:
                  - path: a.js
                    env: [JIRA_TOKEN, CI_PROJECT_ID]
                  - path: b.js
                    env: []
                  - path: c.js
            """.trimIndent()

            val scripts = checkNotNull(parseYaml(yaml).scripts)
            assertEquals(listOf(listOf("JIRA_TOKEN", "CI_PROJECT_ID"), emptyList(), null), scripts.map { it.env })
        }
    }

    @Test
    fun `scripts env that is not a list of names is a config error`() {
        runTest {
            assertFailsWith<ConfigParseException> { parseYaml("scripts:\n  - path: a.js\n    env: JIRA_TOKEN") }
        }
    }

    @Test
    fun `enrichment block parses camelCase keys under ai`() {
        runTest {
            val yaml = """
                ai:
                  enrichment:
                    generateDescription: true
                    generateTitle: true
                    skipIfDescriptionExists: false
                    maxTokens: 3000
            """.trimIndent()

            val config = parseYaml(yaml)
            assertNotNull(config.enrichment)
            assertTrue(config.enrichment!!.generateDescription)
            assertTrue(config.enrichment!!.generateTitle)
            assertFalse(config.enrichment!!.skipIfDescriptionExists)
            assertEquals(3000, config.enrichment!!.maxTokens)
        }
    }

    @Test
    fun `platforms block preserves nested config values`() {
        runTest {
            val yaml = """
                platforms:
                  gitlab:
                    base-url: "https://gitlab.example.com"
                    token: "my-token"
                  github:
                    base-url: "https://api.github.com"
                    token: "gh-token"
                  bitbucket:
                    base-url: "https://bitbucket.org"
            """.trimIndent()

            val config = parseYaml(yaml)
            assertNotNull(config.platforms)
            assertEquals(3, config.platforms!!.size)
            assertEquals(
                "https://gitlab.example.com",
                config.platforms!!["gitlab"]
                    ?.jsonObject
                    ?.get("baseUrl")
                    ?.jsonPrimitive
                    ?.content
            )
            assertEquals(
                "my-token",
                config.platforms!!["gitlab"]
                    ?.jsonObject
                    ?.get("token")
                    ?.jsonPrimitive
                    ?.content
            )
            assertEquals(
                "https://api.github.com",
                config.platforms!!["github"]
                    ?.jsonObject
                    ?.get("baseUrl")
                    ?.jsonPrimitive
                    ?.content
            )
            assertNotNull(config.platforms!!["bitbucket"])
        }
    }

    @Test
    fun `platforms block preserves nested config values for multiple entries`() {
        runTest {
            val yaml = """
                platforms:
                  gitlab:
                    add-labels: true
                    set-assignee: true
                  github:
                    enabled: false
            """.trimIndent()

            val config = parseYaml(yaml)
            assertNotNull(config.platforms)
            assertEquals(2, config.platforms!!.size)
            assertTrue(config.platforms!!.containsKey("gitlab"))
            assertTrue(config.platforms!!.containsKey("github"))
        }
    }

    private companion object {
        val COMBINED_YAML = """
            rules:
              title-format:
                pattern: "^feat:"
              commit-messages:
                max-length: 72

            platforms:
              gitlab:
                base-url: "https://gitlab.example.com"
                token: "test-token"
              github:
                base-url: "https://github.example.com"

            reporters:
              console: true
              json: true
              sarif:
                output: "results.sarif"

            profile: conventional-commits

            scripts:
              - path: ".karandash/checks.js"
              - path: ".karandash/security.js"
                mode: subprocess
                timeout: "15s"

            dangerfile: "Dangerfile.js"
            baseline: "findings-baseline.json"

            dry-run: true
            dry-run-scope: ai

            allow-localhost: true
            allow-private-network: true

            ai:
              model: "claude-sonnet-4-20250514"
              max-tokens: 8000
              enrichment:
                generate-description: true
                generate-title: false
                skip-if-description-exists: true
                max-tokens: 4000

            cache:
              responses:
                ttl: "120s"
              memory:
                max-entries: 256
        """.trimIndent()
    }
}
