package io.karandash.validators

import io.karandash.config.pipeline.ConfigChainParser
import io.karandash.config.pipeline.ConfigChainResolver
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.manifest.ConfigSource
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import io.karandash.testfixtures.RemoteExtendsTestSupport
import io.karandash.testfixtures.RemoteExtendsTestSupport.BASE_CONFIG_YAML
import io.karandash.testfixtures.RemoteExtendsTestSupport.MOCK_BASE_URL
import io.karandash.testfixtures.RemoteExtendsTestSupport.TEST_AUTH_TOKEN
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests verifying that the project project config works correctly
 * when extending a remote base config via HTTP (simulated with MockEngine).
 *
 * Covers: remote extends resolution, auth headers, env interpolation,
 * named pattern expansion, rule completeness, and finding content.
 */
class ProjectRemoteExtendsTest {
    private lateinit var tempDir: Path

    private val projectConfig = RemoteExtendsTestSupport.projectConfig(
        extendsUrl = MOCK_BASE_URL,
        jiraKey = ProjectSpec.KEY,
    )

    @BeforeTest
    fun setup() {
        tempDir = TestFiles.createTestTempDirectory("project-remote")
    }

    @AfterTest
    fun cleanup() {
        deleteDirRecursively(tempDir)
    }

    // ── Config chain resolves via remote extends ──

    @Test
    fun `config chain resolves with 2 entries via remote extends`() =
        runTest {
            val configPath = writeConfig(projectConfig)
            val fetcher = RemoteExtendsTestSupport.mockConfigFetcher(
                responses = mapOf(MOCK_BASE_URL to BASE_CONFIG_YAML),
            )
            val registry = formatRegistry()
            val chain = ConfigChainResolver(registry, fetcher = fetcher)
                .buildChain(ConfigSource(configPath.toString()))

            assertEquals(2, chain.size)
            // Parent (base) is first, child (project) is last
            assertTrue(
                chain.entries[0]
                    .source.url
                    .contains("example.com")
            )
            assertTrue(
                chain.entries[1]
                    .source.url
                    .contains("project-remote")
            )
        }

    // ── Auth header passed correctly ──

    @Test
    fun `custom-header auth is passed in remote fetch`() =
        runTest {
            val configPath = writeConfig(projectConfig)
            val fetcher = RemoteExtendsTestSupport.mockConfigFetcher(
                responses = mapOf(MOCK_BASE_URL to BASE_CONFIG_YAML),
                expectedAuthHeader = "PRIVATE-TOKEN" to TEST_AUTH_TOKEN,
            )
            val registry = formatRegistry()
            val chain = ConfigChainResolver(registry, fetcher = fetcher)
                .buildChain(ConfigSource(configPath.toString()))

            // If auth header was wrong, mockConfigFetcher would have thrown
            assertEquals(2, chain.size)
        }

    // ── All expected rules present after merge ──

    @Test
    fun `all base rules present after remote merge`() =
        runTest {
            val merged = resolveAndMerge()
            val config = ConfigMapParser.parseFullConfig(merged).config

            for (ruleId in RemoteExtendsTestSupport.BASE_RULE_IDS) {
                assertNotNull(
                    config.rules.ruleConfigs[ruleId],
                    "Expected rule '$ruleId' missing after remote extends merge",
                )
            }

            // Shared-suite projects are base-only (no KMP), so context-tag should NOT be present
            assertNull(config.rules.ruleConfigs["contextTag"])
        }

    // ── Env interpolation resolves JIRA key ──

    @Test
    fun `env interpolation resolves project jira key in rules`() =
        runTest {
            val config = resolveAndParse()

            // jira-ticket projectKeys should contain "project" not "${KARANDASH_JIRA_KEY}"
            val jiraTicket = config.rules.ruleConfigs["jiraTicket"]?.jsonObject
            assertNotNull(jiraTicket, "jiraTicket rule config missing")

            val projectKeys = jiraTicket["projectKeys"]?.jsonArray
            assertNotNull(projectKeys, "projectKeys missing in jiraTicket")
            val firstKey = projectKeys[0].jsonPrimitive.content
            assertEquals(ProjectSpec.KEY, firstKey, "JIRA key should be interpolated to '${ProjectSpec.KEY}'")
        }

    // ── Suggestions contain interpolated values ──

    @Test
    fun `suggestions contain resolved jira key not raw env vars`() =
        runTest {
            val config = resolveAndParse()

            // Walk rule configs for suggestion/explanation fields
            for ((ruleName, ruleConfig) in config.rules.ruleConfigs) {
                if (ruleConfig !is JsonObject) continue
                val suggestion = ruleConfig["suggestion"]
                if (suggestion is JsonPrimitive) {
                    assertTrue(
                        !suggestion.content.contains("\${"),
                        "Rule '$ruleName' suggestion contains unresolved env var: ${suggestion.content}",
                    )
                }
                val explanation = ruleConfig["explanation"]
                if (explanation is JsonPrimitive) {
                    assertTrue(
                        !explanation.content.contains("\${"),
                        "Rule '$ruleName' explanation contains unresolved env var: ${explanation.content}",
                    )
                }
            }
        }

    // ── Helpers ──

    /** Env defaults that simulate CI/CD env vars for project. */
    private val envDefaults = mapOf(
        "KARANDASH_GITLAB_TOKEN" to "test-token",
        "BIFROST_API_KEY" to "test-bifrost-key",
        "BIFROST_BASE_URL" to "https://bifrost.example.com",
        "KARANDASH_BASE_TOKEN" to "test-base-token",
        "KARANDASH_JIRA_KEY" to ProjectSpec.KEY,
        "KARANDASH_CONFIG" to ".karandash",
        "KARANDASH_AI_MAX_TOKENS" to "${ProjectSpec.MAX_TOKENS}",
    )

    private suspend fun resolveAndMerge(): kotlinx.serialization.json.JsonElement {
        val configPath = writeConfig(projectConfig)
        val fetcher = RemoteExtendsTestSupport.mockConfigFetcher(
            responses = mapOf(MOCK_BASE_URL to BASE_CONFIG_YAML),
        )
        val registry = formatRegistry()
        val chain = ConfigChainResolver(registry, fetcher = fetcher)
            .buildChain(ConfigSource(configPath.toString()))
        return ConfigChainParser(registry, fetcher = fetcher)
            .parseAndMerge(chain)
    }

    private suspend fun resolveAndParse(): io.karandash.config.Config {
        val merged = resolveAndMerge()
        return ConfigMapParser.parseFullConfig(merged, envDefaults).config
    }

    private fun writeConfig(yaml: String): Path {
        val path = Path(tempDir, "config.yaml")
        SystemFileSystem.sink(path).buffered().use { sink ->
            sink.write(yaml.encodeToByteArray())
            sink.flush()
        }
        return path
    }

    private fun formatRegistry(): ConfigFormatRegistry = ConfigFormatRegistry(listOf(YamlFormatModule()))

    private fun deleteDirRecursively(dir: Path) {
        if (!SystemFileSystem.exists(dir)) return
        val meta = SystemFileSystem.metadataOrNull(dir)
        if (meta?.isDirectory == true) {
            SystemFileSystem.list(dir).forEach { deleteDirRecursively(it) }
        }
        SystemFileSystem.delete(dir)
    }
}
