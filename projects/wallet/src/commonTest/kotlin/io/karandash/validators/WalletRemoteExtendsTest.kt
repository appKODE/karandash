package io.karandash.validators

import io.karandash.config.pipeline.ConfigChainParser
import io.karandash.config.pipeline.ConfigChainResolver
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.manifest.ConfigSource
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import io.karandash.testfixtures.RemoteExtendsTestSupport
import io.karandash.testfixtures.RemoteExtendsTestSupport.BASE_CONFIG_YAML
import io.karandash.testfixtures.RemoteExtendsTestSupport.BASE_KMP_CONFIG_YAML
import io.karandash.testfixtures.RemoteExtendsTestSupport.MOCK_BASE_KMP_URL
import io.karandash.testfixtures.RemoteExtendsTestSupport.MOCK_BASE_URL
import io.karandash.testfixtures.RemoteExtendsTestSupport.TEST_AUTH_TOKEN
import io.karandash.testfixtures.RemoteExtendsTestSupport.projectConfig
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
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Integration tests verifying that the wallet project config works correctly
 * when extending a remote base-kmp config via HTTP (simulated with MockEngine).
 *
 * Chain: wallet → base-kmp → base (3 levels).
 * MockEngine serves both MOCK_BASE_KMP_URL and MOCK_BASE_URL.
 *
 * Covers: 3-level remote extends resolution, auth headers, env interpolation,
 * KMP rule completeness (base rules + context-tag), and finding content.
 */
class WalletRemoteExtendsTest {
    private lateinit var tempDir: Path

    private val walConfig = projectConfig(
        extendsUrl = MOCK_BASE_KMP_URL,
        jiraKey = "WAL",
        extraYaml = "  KARANDASH_AI_MAX_TOKENS: \"30000\"",
    )

    @BeforeTest
    fun setup() {
        val suffix = "${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(0, Int.MAX_VALUE)}"
        tempDir = TestFiles.createTestTempDirectory("wal-remote")
        SystemFileSystem.createDirectories(tempDir)
    }

    @AfterTest
    fun cleanup() {
        deleteDirRecursively(tempDir)
    }

    // ── Config chain resolves via 3-level remote extends ──

    @Test
    fun `config chain resolves with 3 entries via remote extends`() =
        runTest {
            val configPath = writeConfig(walConfig)
            val fetcher = RemoteExtendsTestSupport.mockConfigFetcher(
                responses = mapOf(
                    MOCK_BASE_KMP_URL to BASE_KMP_CONFIG_YAML,
                    MOCK_BASE_URL to BASE_CONFIG_YAML,
                ),
            )
            val registry = formatRegistry()
            val chain = ConfigChainResolver(registry, fetcher = fetcher)
                .buildChain(ConfigSource(configPath.toString()))

            assertEquals(3, chain.size)
            // Order: grandparent (base) first, parent (base-kmp) middle, child (wallet) last
            assertTrue(
                chain.entries[0]
                    .source.url
                    .contains("example.com")
            )
            assertTrue(
                chain.entries[2]
                    .source.url
                    .contains("wal-remote")
            )
        }

    // ── Auth header passed correctly ──

    @Test
    fun `custom-header auth is passed in remote fetch`() =
        runTest {
            val configPath = writeConfig(walConfig)
            val fetcher = RemoteExtendsTestSupport.mockConfigFetcher(
                responses = mapOf(
                    MOCK_BASE_KMP_URL to BASE_KMP_CONFIG_YAML,
                    MOCK_BASE_URL to BASE_CONFIG_YAML,
                ),
                expectedAuthHeader = "PRIVATE-TOKEN" to TEST_AUTH_TOKEN,
            )
            val registry = formatRegistry()
            val chain = ConfigChainResolver(registry, fetcher = fetcher)
                .buildChain(ConfigSource(configPath.toString()))

            // If auth header was wrong, mockConfigFetcher would have thrown
            assertEquals(3, chain.size)
        }

    // ── All expected rules present after merge ──

    @Test
    fun `all kmp rules present after 3-level remote merge`() =
        runTest {
            val merged = resolveAndMerge()
            val config = ConfigMapParser.parseFullConfig(merged).config

            for (ruleId in RemoteExtendsTestSupport.KMP_RULE_IDS) {
                assertNotNull(
                    config.rules.ruleConfigs[ruleId],
                    "Expected rule '$ruleId' missing after remote extends merge",
                )
            }
        }

    // ── Env interpolation resolves JIRA key ──

    @Test
    fun `env interpolation resolves WAL jira key in rules`() =
        runTest {
            val config = resolveAndParse()

            val jiraTicket = config.rules.ruleConfigs["jiraTicket"]?.jsonObject
            assertNotNull(jiraTicket, "jiraTicket rule config missing")

            val projectKeys = jiraTicket["projectKeys"]?.jsonArray
            assertNotNull(projectKeys, "projectKeys missing in jiraTicket")
            val firstKey = projectKeys[0].jsonPrimitive.content
            assertEquals("WAL", firstKey, "JIRA key should be interpolated to 'WAL'")
        }

    // ── Suggestions contain resolved values ──

    @Test
    fun `suggestions contain resolved values not raw env vars`() =
        runTest {
            val config = resolveAndParse()

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

    // ── 3-level transitive inheritance - base fields survive through base-kmp ──

    @Test
    fun `base fields survive 3-level chain through base-kmp to wallet`() =
        runTest {
            val config = resolveAndParse()

            // title-format.min-length: set in base, not overridden in base-kmp or wallet
            val titleFormat = config.rules.ruleConfigs["titleFormat"]?.jsonObject
            assertNotNull(titleFormat, "titleFormat missing")
            assertEquals("10", titleFormat["minLength"]?.jsonPrimitive?.contentOrNull, "min-length inherited through 2 levels")
            assertEquals("120", titleFormat["maxLength"]?.jsonPrimitive?.contentOrNull, "max-length inherited through 2 levels")

            // bail-on-fail is extracted into ruleMeta, not ruleConfigs
            assertTrue(config.rules.metaFor("titleFormat").bailOnFail, "bail-on-fail inherited through 2 levels")

            // draft-prevention: only in base, not in base-kmp or wallet
            assertNotNull(config.rules.ruleConfigs["draftPrevention"], "draft-prevention inherited through 2 levels")

            // size-limits: only in base
            assertNotNull(config.rules.ruleConfigs["sizeLimits"], "size-limits inherited through 2 levels")

            // labels: only in base
            assertNotNull(config.rules.ruleConfigs["labels"], "labels inherited through 2 levels")

            // platforms.gitlab inherited from base
            assertNotNull(config.platforms, "platforms inherited through 2 levels")
            assertTrue(config.platforms!!.containsKey("gitlab"), "gitlab platform inherited through 2 levels")

            // reporters inherited from base
            assertNotNull(config.reporters, "reporters inherited through 2 levels")
            assertTrue(config.reporters!!.containsKey("markdown"), "markdown reporter inherited through 2 levels")
        }

    // ── Policies include both CHANGELOG and AI gating through 3-level chain ──

    @Test
    fun `policies include android CHANGELOG and AI draft gating through 3-level chain`() =
        runTest {
            val merged = resolveAndMerge()
            val root = merged.jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies, "policies missing after 3-level merge")

            val policyWhens = policies.map { it.jsonObject["when"]?.jsonPrimitive?.content }

            // Policies use MERGE_BY_KEY by default - base-kmp overrides changelog-requirement by name,
            // ai-draft-gating inherited from base. Wallet inherits all policies through the chain.
            assertTrue(
                policyWhens.any { it?.contains("is_draft") == true },
                "AI draft gating policy should be present. Got: $policyWhens",
            )
            assertTrue(
                policyWhens.any { it?.contains("has_tag(android)") == true },
                "Base-kmp android CHANGELOG policy should be present. Got: $policyWhens",
            )
        }

    // ── AI config transitive inheritance ──

    @Test
    fun `ai config inherits base provider and timeout through base-kmp`() =
        runTest {
            val merged = resolveAndMerge()
            val ai = merged.jsonObject["ai"]?.jsonObject
            assertNotNull(ai, "ai block missing")

            // Base AI top-level fields inherited through base-kmp
            assertEquals("bifrost", ai["provider"]?.jsonPrimitive?.content, "provider inherited from base")
            assertEquals("5m", ai["timeout"]?.jsonPrimitive?.content, "timeout inherited from base")

            // Base-kmp overrides scopes - wallet inherits base-kmp's scopes
            val scopes = ai["scopes"]?.jsonObject
            assertNotNull(scopes, "ai.scopes missing")
            assertNotNull(scopes["android"], "android scope from base-kmp")
            assertNotNull(scopes["ios"], "ios scope from base-kmp")

            // severity-overrides from base should survive through base-kmp
            // parseAndMerge normalizes each layer's keys, so merged keys are camelCase.
            val severityOverrides = ai["severityOverrides"]?.jsonArray
            assertNotNull(severityOverrides, "severity-overrides inherited from base through base-kmp")
        }

    // ── Context-tag inherited from base-kmp ──

    @Test
    fun `context-tag config inherited from base-kmp with android and ios`() =
        runTest {
            val config = resolveAndParse()

            val contextTag = config.rules.ruleConfigs["contextTag"]?.jsonObject
            assertNotNull(contextTag, "contextTag should be inherited from base-kmp")

            val allowed = contextTag["allowed"]?.jsonArray
            assertNotNull(allowed, "context-tag.allowed missing")
            val values = allowed.map { it.jsonPrimitive.content }
            assertTrue("android" in values, "android should be in allowed tags")
            assertTrue("ios" in values, "ios should be in allowed tags")
        }

    // ── Helpers ──

    /** Env defaults that simulate CI/CD env vars for wallet. */
    private val envDefaults = mapOf(
        "KARANDASH_GITLAB_TOKEN" to "test-token",
        "BIFROST_API_KEY" to "test-bifrost-key",
        "BIFROST_BASE_URL" to "https://bifrost.example.com",
        "KARANDASH_BASE_TOKEN" to "test-base-token",
        "KARANDASH_JIRA_KEY" to "WAL",
        "KARANDASH_CONFIG" to ".karandash",
        "KARANDASH_AI_MAX_TOKENS" to "30000",
    )

    private suspend fun resolveAndMerge(): kotlinx.serialization.json.JsonElement {
        val configPath = writeConfig(walConfig)
        val fetcher = RemoteExtendsTestSupport.mockConfigFetcher(
            responses = mapOf(
                MOCK_BASE_KMP_URL to BASE_KMP_CONFIG_YAML,
                MOCK_BASE_URL to BASE_CONFIG_YAML,
            ),
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
