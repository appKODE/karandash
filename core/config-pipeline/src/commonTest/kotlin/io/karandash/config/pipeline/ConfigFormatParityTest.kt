package io.karandash.config.pipeline

import io.karandash.plugins.parsers.json.JsonFormatModule
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards against root cause family #5: Config Format Parity Gaps.
 *
 * Parses equivalent YAML and JSON configs through [ConfigMapParser] and verifies
 * the resulting [io.karandash.config.Config] objects are structurally identical.
 *
 * When a feature is added to one parser but not the other, or ConfigMapParser
 * handles a field from one format but not another, these tests will fail.
 *
 * KTS parity is tested separately via KtsFormatModule tests, because KTS uses
 * a fundamentally different parser (ANTLR) with known intentional limitations.
 */
class ConfigFormatParityTest {
    private val yamlParser = YamlFormatModule()
    private val jsonParser = JsonFormatModule()

    private suspend fun parseYaml(yaml: String) = ConfigMapParser.parseFullConfig(yamlParser.parseToJsonElement(yaml)).config

    private suspend fun parseJson(json: String) = ConfigMapParser.parseFullConfig(jsonParser.parseToJsonElement(json)).config

    @Test
    fun `rules section parses identically in YAML and JSON`() {
        runTest {
            val yaml = """
                rules:
                  titleFormat:
                    pattern: "^(feat|fix):"
                  commitMessages:
                    maxLength: 72
                  draftPrevention:
                    blockDraft: true
            """.trimIndent()

            val json = """
                {
                  "rules": {
                    "titleFormat": { "pattern": "^(feat|fix):" },
                    "commitMessages": { "maxLength": 72 },
                    "draftPrevention": { "blockDraft": true }
                  }
                }
            """.trimIndent()

            val yamlConfig = parseYaml(yaml)
            val jsonConfig = parseJson(json)

            assertEquals(
                yamlConfig.rules.ruleConfigs.keys,
                jsonConfig.rules.ruleConfigs.keys,
                "Rule IDs should be identical"
            )
            for (ruleId in yamlConfig.rules.ruleConfigs.keys) {
                assertEquals(
                    yamlConfig.rules.ruleConfigs[ruleId],
                    jsonConfig.rules.ruleConfigs[ruleId],
                    "Config for rule '$ruleId' differs between YAML and JSON"
                )
            }
        }
    }

    @Test
    fun `kebab-case rule IDs normalize identically in both formats`() {
        runTest {
            val yaml = """
                rules:
                  title-format:
                    pattern: "^feat:"
            """.trimIndent()

            val json = """
                { "rules": { "title-format": { "pattern": "^feat:" } } }
            """.trimIndent()

            val yamlConfig = parseYaml(yaml)
            val jsonConfig = parseJson(json)

            assertEquals(
                yamlConfig.rules.ruleConfigs.keys,
                jsonConfig.rules.ruleConfigs.keys,
                "Kebab-case rule IDs should normalize identically"
            )
        }
    }

    @Test
    fun `reporters section parses identically in YAML and JSON`() {
        runTest {
            val yaml = """
                reporters:
                  console: true
                  json: true
                  sarif: true
            """.trimIndent()

            val json = """
                { "reporters": { "console": true, "json": true, "sarif": true } }
            """.trimIndent()

            val yamlConfig = parseYaml(yaml)
            val jsonConfig = parseJson(json)

            assertEquals(
                yamlConfig.reporters?.keys ?: emptySet(),
                jsonConfig.reporters?.keys ?: emptySet(),
                "Reporter IDs should match"
            )
        }
    }

    @Test
    fun `platforms section parses identically in YAML and JSON`() {
        runTest {
            val yaml = """
                platforms:
                  gitlab:
                    baseUrl: "https://gitlab.example.com"
            """.trimIndent()

            val json = """
                { "platforms": { "gitlab": { "baseUrl": "https://gitlab.example.com" } } }
            """.trimIndent()

            val yamlConfig = parseYaml(yaml)
            val jsonConfig = parseJson(json)

            assertEquals(yamlConfig.platforms, jsonConfig.platforms)
        }
    }

    @Test
    fun `rule execution meta parses identically in YAML and JSON`() {
        runTest {
            val yaml = """
                rules:
                  titleFormat:
                    priority: 1
                    bail-on-fail: true
                    pattern: "^feat:"
            """.trimIndent()

            val json = """
                {
                  "rules": {
                    "titleFormat": {
                      "priority": 1,
                      "bail-on-fail": true,
                      "pattern": "^feat:"
                    }
                  }
                }
            """.trimIndent()

            val yamlConfig = parseYaml(yaml)
            val jsonConfig = parseJson(json)

            val yamlMeta = yamlConfig.rules.ruleMeta["titleFormat"]
            val jsonMeta = jsonConfig.rules.ruleMeta["titleFormat"]

            assertNotNull(yamlMeta, "YAML should parse rule meta")
            assertNotNull(jsonMeta, "JSON should parse rule meta")
            assertEquals(yamlMeta.priority, jsonMeta.priority, "priority should match")
            assertEquals(yamlMeta.bailOnFail, jsonMeta.bailOnFail, "bailOnFail should match")
        }
    }

    @Test
    fun `platforms section with multiple entries parses identically in YAML and JSON`() {
        runTest {
            val yaml = """
                platforms:
                  gitlab:
                    enabled: true
                  github:
                    enabled: false
            """.trimIndent()

            val json = """
                { "platforms": { "gitlab": { "enabled": true }, "github": { "enabled": false } } }
            """.trimIndent()

            val yamlConfig = parseYaml(yaml)
            val jsonConfig = parseJson(json)

            assertEquals(yamlConfig.platforms, jsonConfig.platforms)
        }
    }

    @Test
    fun `profile string parses identically in YAML and JSON`() {
        runTest {
            val yaml = """
                profile: conventional-commits
            """.trimIndent()

            val json = """
                { "profile": "conventional-commits" }
            """.trimIndent()

            val yamlConfig = parseYaml(yaml)
            val jsonConfig = parseJson(json)

            assertEquals(yamlConfig.profile, jsonConfig.profile)
        }
    }

    @Test
    fun `standard camelCase rule IDs survive round-trip through both parsers`() {
        runTest {
            // 'conditional' is special-cased (parsed as a list, not a map entry)
            // 'ai' and 'jira' are top-level module IDs with sub-rules
            val standardRuleIds = listOf(
                "titleFormat",
                "commitMessages",
                "commitBody",
                "jiraTicket",
                "branchNaming",
                "fileChanges",
                "codeowners",
                "approval",
                "reviewer",
                "cicdStatus",
                "conflict",
                "draftPrevention",
                "labels",
                "sizeLimits",
                "security",
                "contextTag",
                "descriptionFormat",
                "crossEntityConsistency",
                "languagePolicy",
                "ai",
                "jira",
            )

            val yamlRules = standardRuleIds.joinToString("\n") { "  $it:\n    enabled: true" }
            val yaml = "rules:\n$yamlRules"

            val jsonRules = standardRuleIds.joinToString(", ") { "\"$it\": {\"enabled\": true}" }
            val json = """{ "rules": { $jsonRules } }"""

            val yamlConfig = parseYaml(yaml)
            val jsonConfig = parseJson(json)

            for (ruleId in standardRuleIds) {
                assertTrue(
                    yamlConfig.rules.ruleConfigs.containsKey(ruleId),
                    "YAML config missing rule '$ruleId' after parse",
                )
                assertTrue(
                    jsonConfig.rules.ruleConfigs.containsKey(ruleId),
                    "JSON config missing rule '$ruleId' after parse",
                )
            }

            assertEquals(
                yamlConfig.rules.ruleConfigs.keys,
                jsonConfig.rules.ruleConfigs.keys,
                "Parsed rule IDs should be identical between YAML and JSON",
            )
        }
    }
}
