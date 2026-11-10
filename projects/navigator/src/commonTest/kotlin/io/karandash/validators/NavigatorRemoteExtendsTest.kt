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
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Integration tests verifying that the navigator project config works correctly
 * when extending a remote base config via HTTP (simulated with MockEngine).
 *
 * Chain: navigator → base (2 levels).
 * Navigator adds context-tag (ios/android/core), stricter language-policy,
 * gemini model overrides, and android CHANGELOG policy.
 *
 * Covers: remote extends resolution, auth headers, env interpolation,
 * KMP rule completeness (base rules + context-tag), navigator-specific
 * overrides (checkMrDescription, context-tag allowed values), and finding content.
 */
class NavigatorRemoteExtendsTest {
    private lateinit var tempDir: Path

    /**
     * Navigator config with remote extends pointing to MOCK_BASE_URL.
     * Mirrors projects/navigator/.karandash/config.yaml with the local extends
     * replaced by a remote URL and file: references removed (not resolvable in tests).
     */
    private val navConfig = buildString {
        appendLine("extends:")
        appendLine("  url: \"$MOCK_BASE_URL\"")
        appendLine("  auth:")
        appendLine("    type: custom-header")
        appendLine("    name: \"PRIVATE-TOKEN\"")
        appendLine("    value: \"$TEST_AUTH_TOKEN\"")
        appendLine()
        appendLine("env:")
        appendLine("  KARANDASH_JIRA_KEY: \"NAV\"")
        appendLine("  KARANDASH_AI_MAX_TOKENS: \"30000\"")
        appendLine()
        appendLine("patterns:")
        appendLine("  project-ticket: \"@jira-ticket(@project:\${KARANDASH_JIRA_KEY})\"")
        appendLine("  project-tag: \"@bracketed((?:ios|android|core))\"")
        appendLine("  project-title: \"@optional(@project-tickets)@project-tag .+\"")
        appendLine()
        appendLine("rules:")
        appendLine("  title-format:")
        appendLine("    pattern: \"@project-title\"")
        appendLine("    suggestion: \"Examples: '[ios] Add login screen', '[NAV-123][ios] Add login screen'.\"")
        appendLine("    explanation: \"Consistent MR titles make git history searchable.\"")
        appendLine("  commit-messages:")
        appendLine("    pattern: \"@project-title\"")
        appendLine("    suggestion: \"Examples: '[ios] Implement login UI', '[NAV-123][android] Fix tile bugs'. Max 72 chars.\"")
        appendLine("    explanation: \"Uniform commit messages improve git log readability.\"")
        appendLine("  context-tag:")
        appendLine("    allowed: [ios, android, core]")
        appendLine("    delimiter: brackets")
        appendLine("    exactlyOne: true")
        appendLine("    caseSensitive: true")
        appendLine("    checkMrTitle: true")
        appendLine("    checkCommitTitles: true")
        appendLine("    suggestion: \"Add exactly one tag: [ios], [android], or [core].\"")
        appendLine("    explanation: \"Context tags route MRs to platform-specific reviewers.\"")
        appendLine("  cross-entity-consistency:")
        appendLine("    ticket-format: jira")
        appendLine("    ticket-projects: [\"\${KARANDASH_JIRA_KEY}\"]")
        appendLine("  language-policy:")
        appendLine("    checkMrDescription: true")
        appendLine("    checkCommitDescription: true")
        appendLine("    suggestion: \"Replace non-Latin characters with English equivalents.\"")
        appendLine("    explanation: \"English-only text ensures all team members can read git-flow information.\"")
        appendLine()
        appendLine("policies:")
        appendLine("  - when: 'title_matches(\"@any-ticket\") && has_tag(android)'")
        appendLine("    override:")
        appendLine("      commit-body:")
        appendLine("        pattern: \"@changelog-line\"")
        appendLine("        required-when-subject-matches: \"@any-ticket\"")
        appendLine("        matchMode: \"fullMatch\"")
        appendLine("        suggestion: \"Commit body must contain: 'CHANGELOG: [NAV-123] Task name.'\"")
        appendLine("        explanation: \"Structured commit bodies enable automated changelog generation.\"")
        appendLine("  - when: 'is_draft'")
        appendLine("    modules:")
        appendLine("      - risk-scoring")
        appendLine("      - review-comments")
        appendLine("      - code-suggestions")
        appendLine("      - review-thread-analysis")
        appendLine("      - mr-summary")
        appendLine("    enabled: false")
        appendLine()
        appendLine("ai:")
        appendLine("  models:")
        appendLine("    review-thread-analysis: \"openrouter/google/gemini-2.0-flash-001\"")
        appendLine("    risk-scoring: \"openrouter/google/gemini-2.0-flash-001\"")
        appendLine("  scopes:")
        appendLine("    android:")
        appendLine("      paths: [\"android/**/*.{kt,java}\"]")
        appendLine("    ios:")
        appendLine("      paths: [\"iphone/**/*.{swift,m,mm,h}\"]")
        appendLine("    core:")
        appendLine("      paths: [\"core/**/*.{cpp,hpp,h}\"]")
    }

    @BeforeTest
    fun setup() {
        val suffix = "${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(0, Int.MAX_VALUE)}"
        tempDir = TestFiles.createTestTempDirectory("nav-remote")
        SystemFileSystem.createDirectories(tempDir)
    }

    @AfterTest
    fun cleanup() {
        deleteDirRecursively(tempDir)
    }

    // ── Config chain resolves via remote extends ──

    @Test
    fun `config chain resolves with 2 entries via remote extends`() =
        runTest {
            val configPath = writeConfig(navConfig)
            val fetcher = RemoteExtendsTestSupport.mockConfigFetcher(
                responses = mapOf(MOCK_BASE_URL to BASE_CONFIG_YAML),
            )
            val registry = formatRegistry()
            val chain = ConfigChainResolver(registry, fetcher = fetcher)
                .buildChain(ConfigSource(configPath.toString()))

            assertEquals(2, chain.size)
            // Parent (base) is first, child (navigator) is last
            assertTrue(
                chain.entries[0]
                    .source.url
                    .contains("example.com")
            )
            assertTrue(
                chain.entries[1]
                    .source.url
                    .contains("nav-remote")
            )
        }

    // ── Auth header passed correctly ──

    @Test
    fun `custom-header auth is passed in remote fetch`() =
        runTest {
            val configPath = writeConfig(navConfig)
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
    fun `all kmp rules present after remote merge`() =
        runTest {
            val merged = resolveAndMerge()
            val config = ConfigMapParser.parseFullConfig(merged).config

            for (ruleId in RemoteExtendsTestSupport.KMP_RULE_IDS) {
                assertNotNull(
                    config.rules.ruleConfigs[ruleId],
                    "Expected rule '$ruleId' missing after remote extends merge",
                )
            }

            // context-tag is navigator-specific - verify it is present with ios/android/core
            val contextTag = config.rules.ruleConfigs["contextTag"]?.jsonObject
            assertNotNull(contextTag, "context-tag rule must be present in navigator config")
            val allowed = contextTag["allowed"]?.jsonArray
            assertNotNull(allowed, "context-tag.allowed must be present")
            val allowedValues = allowed.map { it.jsonPrimitive.content }
            assertTrue("core" in allowedValues, "context-tag.allowed must contain 'core'")
        }

    // ── Env interpolation resolves JIRA key ──

    @Test
    fun `env interpolation resolves NAV jira key in rules`() =
        runTest {
            val config = resolveAndParse()

            val jiraTicket = config.rules.ruleConfigs["jiraTicket"]?.jsonObject
            assertNotNull(jiraTicket, "jiraTicket rule config missing")

            val projectKeys = jiraTicket["projectKeys"]?.jsonArray
            assertNotNull(projectKeys, "projectKeys missing in jiraTicket")
            val firstKey = projectKeys[0].jsonPrimitive.content
            assertEquals("NAV", firstKey, "JIRA key should be interpolated to 'NAV'")
        }

    // ── Navigator-specific overrides applied ──

    @Test
    fun `navigator overrides applied checkMrDescription true and suggestions resolved`() =
        runTest {
            val config = resolveAndParse()

            // Navigator enables checkMrDescription (base sets it false)
            val languagePolicy = config.rules.ruleConfigs["languagePolicy"]?.jsonObject
            assertNotNull(languagePolicy, "languagePolicy rule config missing")
            val checkMrDescription = languagePolicy["checkMrDescription"]?.jsonPrimitive?.content
            assertEquals("true", checkMrDescription, "Navigator should override checkMrDescription to true")

            // Suggestions/explanations are in ruleMeta - check for unresolved env vars
            for (ruleId in config.rules.ruleConfigs.keys) {
                val ruleMeta = config.rules.metaFor(ruleId)
                ruleMeta.suggestion?.let { text ->
                    assertTrue(
                        !text.contains("\${"),
                        "Rule '$ruleId' suggestion contains unresolved env var: $text",
                    )
                }
                ruleMeta.explanation?.let { text ->
                    assertTrue(
                        !text.contains("\${"),
                        "Rule '$ruleId' explanation contains unresolved env var: $text",
                    )
                }
            }
        }

    // ── Deep merge preserves base sub-fields not overridden by child ──

    @Test
    fun `deep merge preserves base min-length max-length and bail-on-fail in title-format`() =
        runTest {
            val config = resolveAndParse()

            val titleFormat = config.rules.ruleConfigs["titleFormat"]?.jsonObject
            assertNotNull(titleFormat, "titleFormat rule config missing")

            // Navigator overrides pattern and suggestion, but NOT min-length, max-length
            assertEquals("10", titleFormat["minLength"]?.jsonPrimitive?.contentOrNull, "min-length inherited from base")
            assertEquals("120", titleFormat["maxLength"]?.jsonPrimitive?.contentOrNull, "max-length inherited from base")

            // bail-on-fail is extracted into ruleMeta, not ruleConfigs
            assertTrue(config.rules.metaFor("titleFormat").bailOnFail, "bail-on-fail inherited from base")

            // Suggestion is extracted into ruleMeta - verify navigator's override is used
            val meta = config.rules.metaFor("titleFormat")
            assertNotNull(meta.suggestion, "suggestion should be present in ruleMeta")
            assertTrue(
                meta.suggestion!!.contains("[ios]") || meta.suggestion!!.contains("NAV"),
                "suggestion should be navigator's override, got: ${meta.suggestion}",
            )
        }

    // ── Navigator policies include both CHANGELOG and AI gating ──

    @Test
    fun `navigator policies include android CHANGELOG and AI draft gating`() =
        runTest {
            val merged = resolveAndMerge()
            val root = merged.jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies, "policies missing after merge")

            // Policies use MERGE_BY_KEY by default - navigator overrides changelog-requirement by name,
            // ai-draft-gating inherited from base.
            val policyWhens = policies.map { it.jsonObject["when"]?.jsonPrimitive?.content }

            assertTrue(
                policyWhens.any { it?.contains("is_draft") == true },
                "AI draft gating policy should be present. Got: $policyWhens",
            )
            assertTrue(
                policyWhens.any { it?.contains("has_tag(android)") == true },
                "Navigator CHANGELOG policy should be present. Got: $policyWhens",
            )
        }

    // ── Patterns merge - base patterns inherited, child patterns override ──

    @Test
    fun `patterns merge preserves base changelog-line and adds navigator project-tag`() =
        runTest {
            val merged = resolveAndMerge()
            val root = merged.jsonObject
            val patterns = root["patterns"]?.jsonObject
            assertNotNull(patterns, "patterns missing after merge")

            // Navigator adds project-tag, overrides project-title
            assertNotNull(patterns["project-tag"], "project-tag should be added from navigator")

            // Base patterns should be inherited
            assertNotNull(patterns["changelog-line"], "changelog-line should be inherited from base")
            assertNotNull(patterns["changelog-entry"], "changelog-entry should be inherited from base")
            assertNotNull(patterns["project-tickets"], "project-tickets should be inherited from base")
        }

    // ── AI config merge - model overrides + inherited fields ──

    @Test
    fun `ai config merges navigator model overrides with base defaults`() =
        runTest {
            val merged = resolveAndMerge()
            val ai = merged.jsonObject["ai"]?.jsonObject
            assertNotNull(ai, "ai block missing")

            // Navigator overrides review-thread-analysis and risk-scoring models
            // parseAndMerge normalizes each layer's keys, so merged keys are camelCase.
            val models = ai["models"]?.jsonObject
            assertNotNull(models, "ai.models missing")
            assertEquals(
                "openrouter/google/gemini-2.0-flash-001",
                models["reviewThreadAnalysis"]?.jsonPrimitive?.content,
                "review-thread-analysis should be navigator's gemini override",
            )
            assertEquals(
                "openrouter/google/gemini-2.0-flash-001",
                models["riskScoring"]?.jsonPrimitive?.content,
                "risk-scoring should be navigator's gemini override",
            )
            // mr-summary should be inherited from base (not overridden by navigator)
            assertEquals(
                "openrouter/qwen/qwen3.5-35b-a3b",
                models["mrSummary"]?.jsonPrimitive?.content,
                "mr-summary should be inherited from base",
            )

            // Base AI top-level fields should be inherited
            assertEquals("bifrost", ai["provider"]?.jsonPrimitive?.content, "provider inherited from base")
            assertEquals("5m", ai["timeout"]?.jsonPrimitive?.content, "timeout inherited from base")

            // AI scopes: navigator overrides android/ios/core, base ci should be inherited
            val scopes = ai["scopes"]?.jsonObject
            assertNotNull(scopes, "ai.scopes missing")
            assertNotNull(scopes["ci"], "base ci scope should be inherited")
            assertNotNull(scopes["android"], "navigator android scope should be present")
            assertNotNull(scopes["core"], "navigator core scope should be present")

            // severity-overrides (array) from base should be inherited (navigator doesn't define it)
            val severityOverrides = ai["severityOverrides"]?.jsonArray
            assertNotNull(severityOverrides, "base severity-overrides should be inherited")
        }

    // ── Platform and reporter config inherited from base ──

    @Test
    fun `platform and reporter config inherited from base`() =
        runTest {
            val config = resolveAndParse()

            // Platforms inherited
            assertNotNull(config.platforms, "platforms should be inherited from base")
            assertTrue(config.platforms!!.containsKey("gitlab"), "gitlab platform should be inherited")

            // Reporters inherited
            assertNotNull(config.reporters, "reporters should be inherited from base")
            assertTrue(config.reporters!!.containsKey("markdown"), "markdown reporter should be inherited")
        }

    // ── Helpers ──

    /** Env defaults that simulate CI/CD env vars for navigator. */
    private val envDefaults = mapOf(
        "KARANDASH_GITLAB_TOKEN" to "test-token",
        "BIFROST_API_KEY" to "test-bifrost-key",
        "BIFROST_BASE_URL" to "https://bifrost.example.com",
        "KARANDASH_BASE_TOKEN" to "test-base-token",
        "KARANDASH_JIRA_KEY" to "NAV",
        "KARANDASH_CONFIG" to ".karandash",
        "KARANDASH_AI_MAX_TOKENS" to "30000",
    )

    private suspend fun resolveAndMerge(): kotlinx.serialization.json.JsonElement {
        val configPath = writeConfig(navConfig)
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
