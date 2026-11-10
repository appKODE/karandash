package io.karandash.validators

import io.karandash.config.pipeline.ConfigChainParser
import io.karandash.config.pipeline.ConfigChainResolver
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.manifest.ConfigSource
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import io.karandash.testfixtures.RemoteExtendsTestSupport
import io.karandash.testfixtures.RemoteExtendsTestSupport.BASE_CONFIG_YAML
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Integration tests verifying that the base config works correctly as a standalone
 * config (no extends). Validates rule completeness and env interpolation.
 */
class BaseRemoteExtendsTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        val suffix = "${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(0, Int.MAX_VALUE)}"
        tempDir = TestFiles.createTestTempDirectory("base-standalone")
        SystemFileSystem.createDirectories(tempDir)
    }

    @AfterTest
    fun cleanup() {
        deleteDirRecursively(tempDir)
    }

    // ── Config chain is size 1 (no extends) ──

    @Test
    fun `standalone base config produces chain of size 1`() =
        runTest {
            val configPath = writeConfig(BASE_CONFIG_YAML)
            val fetcher = RemoteExtendsTestSupport.mockConfigFetcher(emptyMap())
            val registry = formatRegistry()
            val chain = ConfigChainResolver(registry, fetcher = fetcher)
                .buildChain(ConfigSource(configPath.toString()))

            assertEquals(1, chain.size)
        }

    // ── All expected base rules present ──

    @Test
    fun `all base rules present in standalone config`() =
        runTest {
            val config = parseConfig()

            for (ruleId in RemoteExtendsTestSupport.BASE_RULE_IDS) {
                assertNotNull(
                    config.rules.ruleConfigs[ruleId],
                    "Expected rule '$ruleId' missing in base config",
                )
            }

            // Base config should NOT have context-tag (that's KMP-only)
            assertNull(config.rules.ruleConfigs["contextTag"])
        }

    // ── Env interpolation resolves JIRA key ──

    @Test
    fun `env interpolation resolves PROJ jira key`() =
        runTest {
            val config = parseConfig()

            val jiraTicket = config.rules.ruleConfigs["jiraTicket"]?.jsonObject
            assertNotNull(jiraTicket, "jiraTicket rule config missing")

            val projectKeys = jiraTicket!!["projectKeys"]?.jsonArray
            assertNotNull(projectKeys, "projectKeys missing in jiraTicket")
            val firstKey = projectKeys!![0].jsonPrimitive.content
            assertEquals("PROJ", firstKey, "JIRA key should be interpolated to 'PROJ'")
        }

    // ── No unresolved env vars in suggestions ──

    @Test
    fun `suggestions contain resolved values not raw env vars`() =
        runTest {
            val config = parseConfig()

            for ((ruleName, ruleConfig) in config.rules.ruleConfigs) {
                if (ruleConfig !is JsonObject) continue
                val suggestion = (ruleConfig as JsonObject)["suggestion"]
                if (suggestion is JsonPrimitive) {
                    assertTrue(
                        !suggestion.content.contains("\${"),
                        "Rule '$ruleName' suggestion contains unresolved env var: ${suggestion.content}",
                    )
                }
                val explanation = (ruleConfig as JsonObject)["explanation"]
                if (explanation is JsonPrimitive) {
                    assertTrue(
                        !explanation.content.contains("\${"),
                        "Rule '$ruleName' explanation contains unresolved env var: ${explanation.content}",
                    )
                }
            }
        }

    // ── Helpers ──

    private val envDefaults = mapOf(
        "KARANDASH_GITLAB_TOKEN" to "test-token",
        "BIFROST_API_KEY" to "test-bifrost-key",
        "BIFROST_BASE_URL" to "https://bifrost.example.com",
        "KARANDASH_BASE_TOKEN" to "test-base-token",
        "KARANDASH_JIRA_KEY" to "PROJ",
        "KARANDASH_CONFIG" to ".karandash",
        "KARANDASH_AI_MAX_TOKENS" to "20000",
    )

    private suspend fun parseConfig(): io.karandash.config.Config {
        val configPath = writeConfig(BASE_CONFIG_YAML)
        val fetcher = RemoteExtendsTestSupport.mockConfigFetcher(emptyMap())
        val registry = formatRegistry()
        val chain = ConfigChainResolver(registry, fetcher = fetcher)
            .buildChain(ConfigSource(configPath.toString()))
        val merged = ConfigChainParser(registry, fetcher = fetcher)
            .parseAndMerge(chain)
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
