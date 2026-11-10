package io.karandash.validators

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.config.pipeline.isModuleDisabled
import io.karandash.plugins.ai.prompts.PromptSource
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import io.karandash.sdk.config.deserializeConfig
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

private fun writeBaseKmpTestFile(
    path: Path,
    content: String
) {
    SystemFileSystem.sink(path).buffered().use { sink ->
        sink.write(content.encodeToByteArray())
        sink.flush()
    }
}

/**
 * The effective BASE-KMP project config: base + base-kmp overrides merged,
 * with env vars replaced by test-safe values.
 */
private val BASE_KMP_CONFIG_YAML = """
platforms:
  gitlab:
    url: "https://gitlab.example.com"
    token: "test-token"


reporters:
  markdown: true
  console: false

rules:
  title-format:
    pattern: "^(\\[[A-Z]+-\\d+\\])?\\[(android|ios)\\] .+"
    min-length: 10
    max-length: 120
    bail-on-fail: true
    suggestion: "Format: '[PROJ-123][android] Title' or '[ios] Title'. Examples: '[WAL-42][android] Add login screen', '[ios] Fix OTP timer'. Length: 10-120 chars."
    explanation: "Platform tag routes the MR to the correct reviewers and enables per-platform metrics."
  commit-messages:
    pattern: "^(\\[[A-Z]+-\\d+\\])?\\[(android|ios)\\] .+"
    max-length: 72
    bail-on-fail: true
    suggestion: "Format: '[PROJ-123][android] Description' or '[ios] Description'. Examples: '[WAL-42][android] Implement login UI', '[ios] Stop timer on background'. Max 72 chars."
    explanation: "Platform-tagged commits enable per-platform release notes and route issues to the right team."
  jira-ticket:
    when: 'title_matches("\[[A-Z]+-\d+\]")'
    projectKeys: ["PROJ"]
    suggestion: "Use your project's JIRA key in the ticket reference. Example: '[TEL-123]' not '[WAL-123]' in an TEL project."
    explanation: "JIRA references link code changes to project requirements for audit trails and sprint tracking."
  context-tag:
    allowed: ["android", "ios"]
    exactlyOne: true
    caseSensitive: false
    checkMrTitle: true
    checkCommitTitles: true
    suggestion: "Add exactly one tag: [android] for Android, or [ios] for iOS. Place it after the optional task ID in both MR title and each commit."
    explanation: "Context tags route MRs to platform-specific reviewers and feed per-platform metrics dashboards."
  cross-entity-consistency:
    taskIdPattern: "[A-Z]+-\\d+"
    commitTasksSubsetOfMr: true
    changelogConsistency: true
    changelogPrefix: "CHANGELOG:"
    mrTaskSource: "title"
    changelogDuplicateRequiresMatchingText: true
    suggestion: "All task IDs in commits must also appear in the MR title. If a commit references [PROJ-999], the MR title must include [PROJ-999] too."
    explanation: "Orphaned task references cause broken JIRA links and inaccurate sprint reports."
  draft-prevention:
    enabled: true
    suggestion: "In GitLab, click 'Mark as ready'. Remove 'WIP:', '[WIP]', or 'Draft:' from the title if present."
    explanation: "Merging incomplete work can break the main branch and block other developers."
  labels:
    requiredLabels: []
    suggestion: "No labels required currently. When labels are configured, apply them via the MR sidebar in GitLab."
    explanation: "Labels categorize MRs for filtering, metrics, and automated workflow routing."
  size-limits:
    maxFiles: 75
    maxLines: 2000
    maxCommits: 30
    suggestion: "Split large MRs into smaller focused ones. Aim for a single logical change per MR."
    explanation: "Large MRs are harder to review thoroughly and increase the risk of bugs slipping through."
  language-policy:
    allowedScripts: ["latin"]
    checkMrTitle: true
    checkCommitTitle: true
    checkBranchName: true
    checkMrDescription: false
    checkCommitDescription: false
    suggestion: "Replace non-Latin characters with English equivalents in MR title, branch name, and commit title."
    explanation: "English-only text ensures all team members can read git-flow information regardless of native language."
policies:
  - when: 'has_ticket(PROJ) && has_tag(android)'
    override:
      commit-body:
        pattern: "^CHANGELOG: (\\[[A-Z]+-\\d+\\] .+)(, \\[[A-Z]+-\\d+\\] .+)*\\.${'$'}"
        required: true
        matchMode: "fullMatch"
        suggestion: "Add CHANGELOG line ending with a period: 'CHANGELOG: [PROJ-123] Description.' or for multiple tasks: 'CHANGELOG: [PROJ-123] Task one, [PROJ-456] Task two.' (description can be in Russian)"
        explanation: "CHANGELOG lines for Android changes enable per-platform release notes generation."

cache:
  enabled: false

ai:
  provider: "bifrost"
  model: "openrouter/moonshotai/kimi-k2.5"
  api-key: "test-bifrost-key"
  base-url: "https://bifrost.example.com"
  max-token-budget: 20000
  fail-on-error: false
  timeout: "5m"
  execution-timeout: "6m"
  max-retries: 1
  priority: 200
  mode: supplement
  response-language: "English"
  strictness: "standard"
  models:
    review-thread-analysis: "openrouter/qwen/qwen3.5-35b-a3b"
    mr-summary: "openrouter/qwen/qwen3.5-35b-a3b"
    risk-scoring: "openrouter/qwen/qwen3.5-35b-a3b"
  risk-scoring:
    enabled: true
    model: "openrouter/qwen/qwen3.5-35b-a3b"
  review-comments:
    enabled: true
  code-suggestions:
    enabled: true
    mode: "inline"
    min-severity: "warning"
  review-thread-analysis:
    enabled: true
  mr-summary:
    enabled: true
  cost-budget:
    max-tokens-per-run: 20000
    on-budget-exceeded: "skip-remaining"
  grounding:
    check-symbol-existence: true
    check-code-snippets: true
    auto-downgrade-refuted: true
  filter:
    max-per-rule: 10
    max-per-file: 5
    min-confidence: 0.3
    deduplicate: true
  size-policy:
    skip:
      maxFiles: 500
      maxTotalLines: 50000
      maxCommits: 200
    exclude:
      patterns:
        - "**/generated/**"
        - "**/*.generated.*"
        - "**/build/**"
        - "**/.gradle/**"
        - "**/Pods/**"
        - "**/package-lock.json"
        - "**/*.md"
    partial-review:
      maxFiles: 40
      strategy: "most-changed"
    diff-preprocessor:
      skip-deleted-files: true
      skip-pure-renames: true
      strip-diff-metadata: true
      skip-binary-diffs: true
  extra-instructions: "Project-specific review instructions for test."
  scopes:
    android:
      paths: ["android/**"]
    ios:
      paths: ["ios/**/*.swift", "ios/**/*.m", "ios/**/*.mm", "ios/**/*.h"]
      instructions: "iOS-specific review instructions."
      best-practices: "iOS best practices for code review."
    shared:
      paths: ["shared/**/*.kt"]
      instructions: "Shared module review instructions."
      best-practices: "Shared module best practices for code review."
""".trimIndent()

// =========================================================================
// CONFIG PARSING
// =========================================================================

class BaseKmpYamlConfigParsingTest {
    @Test
    fun `base-kmp config contains all expected rule entries including context-tag`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            assertNotNull(config.rules.ruleConfigs["titleFormat"])
            assertNotNull(config.rules.ruleConfigs["commitMessages"])
            assertNotNull(config.rules.ruleConfigs["jiraTicket"])
            assertNotNull(config.rules.ruleConfigs["contextTag"])
            assertNotNull(config.rules.ruleConfigs["crossEntityConsistency"])
            assertNotNull(config.rules.ruleConfigs["draftPrevention"])
            assertNotNull(config.rules.ruleConfigs["labels"])
            assertNotNull(config.rules.ruleConfigs["sizeLimits"])
            assertNotNull(config.rules.ruleConfigs["languagePolicy"])
        }
    }

    @Test
    fun `title-format rule has KMP pattern with platform tags`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<TitleFormatConfig>(config.rules.ruleConfigs["titleFormat"]!!)

            assertEquals("^(\\[[A-Z]+-\\d+\\])?\\[(android|ios)\\] .+", typed.pattern)
            assertEquals(10, typed.minLength)
            assertEquals(120, typed.maxLength)
        }
    }

    @Test
    fun `context-tag rule allows exactly android and ios`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<ContextTagConfig>(config.rules.ruleConfigs["contextTag"]!!)

            assertEquals(listOf("android", "ios"), typed.allowed)
            assertTrue(typed.exactlyOne)
            assertTrue(typed.checkMrTitle)
            assertTrue(typed.checkCommitTitles)
            assertFalse(typed.caseSensitive)
        }
    }

    @Test
    fun `commit-body rule is not configured globally`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            assertTrue(config.rules.ruleConfigs["commitBody"] == null)
        }
    }

    @Test
    fun `android policy defines commit-body with changelog pattern`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)

            val androidPolicy = policies
                .map { it.jsonObject }
                .firstOrNull { it["when"]?.jsonPrimitive?.content?.contains("android") == true }
            assertNotNull(androidPolicy)

            val override = androidPolicy["override"]?.jsonObject
            assertNotNull(override)
            val commitBody = override["commit-body"]?.jsonObject
            assertNotNull(commitBody)
            assertEquals("fullMatch", commitBody["matchMode"]?.jsonPrimitive?.content)
            assertEquals(true, commitBody["required"]?.jsonPrimitive?.boolean)
            assertTrue(commitBody["pattern"]?.jsonPrimitive?.content?.contains("CHANGELOG") == true)
        }
    }

    @Test
    fun `policies block is present and contains one android entry`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            assertEquals(1, policies.size)
            assertTrue(
                policies[0]
                    .jsonObject["when"]
                    ?.jsonPrimitive
                    ?.content
                    ?.contains("android") == true
            )
        }
    }

    @Test
    fun `providers block parses gitlab`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            assertNotNull(config.platforms)
            assertTrue(config.platforms!!.containsKey("gitlab"))
        }
    }

    @Test
    fun `reporters and comment-posters parsed`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            assertNotNull(config.platforms?.get("gitlab"))
            assertNotNull(config.reporters?.get("markdown"))
            assertTrue(isModuleDisabled(config.reporters?.get("console")))
        }
    }

    @Test
    fun `language-policy checkMrDescription is false`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<LanguagePolicyConfig>(config.rules.ruleConfigs["languagePolicy"]!!)

            assertFalse(typed.checkMrDescription)
            assertFalse(typed.checkCommitDescription)
        }
    }
}

// =========================================================================
// AI CONFIG
// =========================================================================

class BaseKmpAiConfigTest {
    private suspend fun aiBlock(): JsonObject {
        val root = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML).jsonObject
        return root["ai"]?.jsonObject ?: error("ai block missing")
    }

    @Test
    fun `ai block is present`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            assertNotNull(config.ai)
        }
    }

    @Test
    fun `ai block parses provider and model`() {
        runTest {
            val ai = aiBlock()
            assertEquals("bifrost", ai["provider"]?.jsonPrimitive?.content)
            assertEquals("openrouter/moonshotai/kimi-k2.5", ai["model"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `ai scopes defines android ios shared`() {
        runTest {
            val ai = aiBlock()
            val scopes = ai["scopes"]?.jsonObject
            assertNotNull(scopes)
            assertNotNull(scopes["android"]?.jsonObject)
            assertNotNull(scopes["ios"]?.jsonObject)
            assertNotNull(scopes["shared"]?.jsonObject)
            val androidPaths = scopes["android"]?.jsonObject?.get("paths")?.jsonArray
            assertNotNull(androidPaths)
            assertTrue(androidPaths.any { it.jsonPrimitive.content == "android/**" })
        }
    }

    @Test
    fun `ai sub-rules are enabled`() {
        runTest {
            val ai = aiBlock()
            val subRules = listOf("risk-scoring", "review-comments", "mr-summary", "review-thread-analysis")
            for (rule in subRules) {
                val block = ai[rule]?.jsonObject
                assertNotNull(block, "$rule block missing")
                assertEquals(true, block["enabled"]?.jsonPrimitive?.boolean, "$rule should be enabled")
            }
        }
    }
}

// =========================================================================
// CACHE CONFIG
// =========================================================================

class BaseKmpCacheConfigTest {
    @Test
    fun `cache block is disabled`() {
        runTest {
            val config = ConfigMapParser
                .parseFullConfig(
                    YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML),
                ).config
            assertNotNull(config.cache)
            assertFalse(config.cache!!.enabled)
        }
    }
}

// =========================================================================
// SUGGESTION / EXPLANATION
// =========================================================================

class BaseKmpSuggestionExplanationTest {
    private suspend fun parseConfig() = ConfigMapParser.parseFullConfig(YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML)).config

    @Test
    fun `title-format meta contains suggestion with platform tag examples`() {
        runTest {
            val meta = parseConfig().rules.metaFor("titleFormat")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("[android]"))
            assertTrue(meta.suggestion!!.contains("[ios]"))
            assertTrue(meta.suggestion!!.contains("10-120"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("reviewers"))
        }
    }

    @Test
    fun `context-tag meta contains suggestion with allowed tags`() {
        runTest {
            val meta = parseConfig().rules.metaFor("contextTag")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("[android]"))
            assertTrue(meta.suggestion!!.contains("[ios]"))
            assertTrue(meta.suggestion!!.contains("exactly one"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("reviewers"))
        }
    }

    @Test
    fun `policy commit-body contains suggestion and explanation`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            val commitBody = policies[0]
                .jsonObject["override"]
                ?.jsonObject
                ?.get("commit-body")
                ?.jsonObject
            assertNotNull(commitBody)
            assertTrue(commitBody["suggestion"]?.jsonPrimitive?.content?.contains("CHANGELOG") == true)
            assertTrue(commitBody["explanation"]?.jsonPrimitive?.content?.contains("release notes") == true)
        }
    }

    @Test
    fun `draft-prevention meta contains suggestion`() {
        runTest {
            val meta = parseConfig().rules.metaFor("draftPrevention")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("Mark as ready"))
        }
    }
}

// =========================================================================
// END-TO-END
// =========================================================================

class BaseKmpConfigE2ETest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = TestFiles.createTestTempDirectory("base-kmp-e2e")
    }

    @AfterTest
    fun cleanup() {
        TestFiles.deleteRecursively(tempDir)
    }

    private var fileCounter = 0

    private fun configFile(): Path {
        val f = Path(tempDir, "config-${fileCounter++}.yaml")
        writeBaseKmpTestFile(f, BASE_KMP_CONFIG_YAML.replace("\nai:\n", "\nai:\n  statistics:\n    dir: \"$tempDir/stats\"\n"))
        return f
    }

    private fun mrFile(json: String): Path {
        val f = Path(tempDir, "mr-${fileCounter++}.json")
        writeBaseKmpTestFile(f, json)
        return f
    }

    // Isolated from the host: no env (KARANDASH_PLUGIN_DIR, tokens, CI vars), no external plugin dirs,
    // no platform auto-detection and no cache, with a fresh bootstrap per run.
    private suspend fun validate(
        config: Path,
        mr: Path
    ): Int {
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        return ValidateCommand(bootstrap)
            .test(
                "--config $config --mr $mr --quiet --no-auto-detect --no-cache",
            ).statusCode
    }

    @Test
    fun `valid android mr with changelog passes`() =
        runTest {
            val mr = mrFile(androidMr("[PROJ-123][android] Add login screen"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `valid ios mr passes`() =
        runTest {
            val mr = mrFile(iosMr("[PROJ-456][ios] Fix OTP timer"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `ios mr with jira but no changelog passes because conditional is android-only`() =
        runTest {
            val mr = mrFile(iosMrNoChangelog("[PROJ-789][ios] Add settings screen"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `android mr with jira but no changelog fails`() =
        runTest {
            val mr = mrFile(androidMrNoChangelog("[PROJ-456][android] Fix map rendering"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `draft mr fails draft prevention`() =
        runTest {
            val mr = mrFile(draftMr("[PROJ-123][ios] WIP login screen"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr with cyrillic text in title fails language policy`() =
        runTest {
            val mr =
                mrFile(androidMr("[PROJ-123][android] \u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u044d\u043a\u0440\u0430\u043d"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `commit with extra task id fails cross entity check`() =
        runTest {
            val mr = mrFile(crossEntityViolation())
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr without platform tag fails`() =
        runTest {
            val mr = mrFile(androidMr("[PROJ-123] Missing platform tag"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    // ---- MR JSON builders ---------------------------------------------------

    private fun androidMr(title: String) =
        """
        {
            "id": "1",
            "iid": 100,
            "title": "$title",
            "description": "Implementation details",
            "source_branch": "feature/PROJ-123-login",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "[PROJ-123][android] Implement feature\n\nCHANGELOG: [PROJ-123] Add login screen.",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun androidMrNoChangelog(title: String) =
        """
        {
            "id": "2",
            "iid": 200,
            "title": "$title",
            "description": "Implementation details",
            "source_branch": "feature/PROJ-456-fix",
            "target_branch": "main",
            "author": { "username": "jane", "name": "Jane Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "def456",
                    "message": "[PROJ-456][android] Fix map rendering",
                    "author": { "name": "Jane", "email": "jane@example.com" },
                    "timestamp": "2024-01-15T11:00:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun iosMr(title: String) =
        """
        {
            "id": "3",
            "iid": 300,
            "title": "$title",
            "description": "iOS implementation",
            "source_branch": "feature/PROJ-456-otp",
            "target_branch": "main",
            "author": { "username": "alice", "name": "Alice" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "ghi789",
                    "message": "[PROJ-456][ios] Fix OTP timer\n\nCHANGELOG: [PROJ-456] OTP timer fix.",
                    "author": { "name": "Alice", "email": "alice@example.com" },
                    "timestamp": "2024-01-15T12:00:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun iosMrNoChangelog(title: String) =
        """
        {
            "id": "6",
            "iid": 600,
            "title": "$title",
            "description": "iOS implementation",
            "source_branch": "feature/PROJ-789-settings",
            "target_branch": "main",
            "author": { "username": "alice", "name": "Alice" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "jkl012",
                    "message": "[PROJ-789][ios] Add settings screen",
                    "author": { "name": "Alice", "email": "alice@example.com" },
                    "timestamp": "2024-01-15T13:00:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun draftMr(title: String) =
        """
        {
            "id": "4",
            "iid": 400,
            "title": "$title",
            "description": "WIP",
            "source_branch": "feature/PROJ-123-wip",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": true,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "[PROJ-123][ios] WIP login\n\nCHANGELOG: [PROJ-123] WIP.",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun crossEntityViolation() =
        """
        {
            "id": "5",
            "iid": 500,
            "title": "[PROJ-123][android] Single task MR",
            "description": "Implementation",
            "source_branch": "feature/PROJ-123",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "[PROJ-123][PROJ-999][android] Work referencing extra task\n\nCHANGELOG: [PROJ-123] Single task.",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()
}

// =========================================================================
// POLICIES MIGRATION TESTS
// =========================================================================

class BaseKmpPoliciesMigrationTest {
    @Test
    fun `base-kmp config with policies block parses successfully`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            assertEquals(9, config.rules.ruleConfigs.size, "rules section must have 9 entries")
        }
    }

    @Test
    fun `policies block targets android platform`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            assertTrue(
                policies[0]
                    .jsonObject["when"]
                    ?.jsonPrimitive
                    ?.content
                    ?.contains("android") == true
            )
        }
    }

    @Test
    fun `policies override contains commit-body rule`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(BASE_KMP_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            val override = policies[0].jsonObject["override"]?.jsonObject
            assertNotNull(override)
            assertTrue(override.containsKey("commit-body"))
        }
    }
}

// =========================================================================
// PROMPT SOURCE REGRESSION
// =========================================================================

class BaseKmpPromptSourceRegressionTest {
    @Test
    fun `prompts review-comments layered resolves to PromptSource Layered`() {
        runTest {
            val promptsYaml = """
                ai:
                  prompts:
                    review-comments:
                      layered:
                        include-builtin: true
                        sources:
                          - file: ".karandash/review-prompt.md"
            """.trimIndent()
            val root = YamlFormatModule().parseToJsonElement(promptsYaml).jsonObject
            val reviewCommentsPrompt = root["ai"]
                ?.jsonObject
                ?.get("prompts")
                ?.jsonObject
                ?.get("review-comments")
            assertNotNull(reviewCommentsPrompt)

            val source = PromptSource.fromJson(reviewCommentsPrompt)
            assertIs<PromptSource.Layered>(source)
            assertEquals(true, source.includeBuiltin)
            assertEquals(1, source.sources.size)
            assertIs<PromptSource.File>(source.sources[0])
        }
    }

    @Test
    fun `prompts review-comments without layered key returns Builtin`() {
        runTest {
            val element = YamlFormatModule().parseToJsonElement("enabled: true")
            val source = PromptSource.fromJson(element)
            assertIs<PromptSource.Builtin>(source)
        }
    }
}
