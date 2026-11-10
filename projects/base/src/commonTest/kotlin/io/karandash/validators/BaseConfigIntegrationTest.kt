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

private fun writeBaseTestFile(
    path: Path,
    content: String
) {
    SystemFileSystem.sink(path).buffered().use { sink ->
        sink.write(content.encodeToByteArray())
        sink.flush()
    }
}

/**
 * The effective BASE project config with env vars replaced by test-safe values.
 * This is the foundation config that all Android-only projects inherit from.
 */
private val BASE_CONFIG_YAML = """
platforms:
  gitlab:
    url: "https://gitlab.example.com"
    token: "test-token"


reporters:
  markdown: true
  console: false

rules:
  title-format:
    pattern: "^(\\[[A-Z]+-\\d+\\] )?.+"
    min-length: 10
    max-length: 120
    bail-on-fail: true
    suggestion: "Format: '[PROJ-123] Title' or just 'Title'. Examples: '[TEL-42] Add device profile screen', 'Fix null pointer in login flow'. Length: 10-120 chars."
    explanation: "Consistent MR titles make git history searchable by task ID and enable automated changelog generation."
  commit-messages:
    pattern: "^(\\[[A-Z]+-\\d+\\] )?.+"
    max-length: 72
    bail-on-fail: true
    suggestion: "Format: '[PROJ-123] Description' or just 'Description'. Examples: '[TEL-42] Implement device profile UI', 'Fix null pointer in login'. Max 72 chars."
    explanation: "Consistent commit messages improve git log readability and enable per-task release notes."
  jira-ticket:
    when: 'title_matches("\[[A-Z]+-\d+\]")'
    projectKeys: ["PROJ"]
    suggestion: "Use your project's JIRA key in the ticket reference. Example: '[TEL-123]' not '[WAL-123]' in an TEL project."
    explanation: "JIRA references link code changes to project requirements for audit trails and sprint tracking."
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
  - when: 'has_ticket(PROJ)'
    override:
      commit-body:
        pattern: "^CHANGELOG: (\\[[A-Z]+-\\d+\\] .+)(, \\[[A-Z]+-\\d+\\] .+)*\\.${'$'}"
        required: true
        matchMode: "fullMatch"
        suggestion: "Add CHANGELOG line ending with a period: 'CHANGELOG: [PROJ-123] Description.' or for multiple tasks: 'CHANGELOG: [PROJ-123] Task one, [PROJ-456] Task two.' (description can be in Russian)"
        explanation: "CHANGELOG lines enable per-platform release notes generation."

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
  stream: true
  priority: 200
  mode: supplement
  response-language: "English"
  strictness: "standard"
  cross-file-context:
    enabled: true
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
      paths: ["**/*.kt", "**/*.java", "**/*.xml", "**/*.gradle.kts"]
      instructions: "Android-specific review instructions."
      best-practices: "Android best practices for code review."
    ci:
      paths: ["ci/**", ".gitlab-ci.yml", ".github/**", "Makefile"]
      instructions: "CI-specific review instructions."
""".trimIndent()

// =========================================================================
// CONFIG PARSING
// =========================================================================

class BaseYamlConfigParsingTest {
    @Test
    fun `base config yaml contains all 9 expected top-level rule entries`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            assertNotNull(config.rules.ruleConfigs["titleFormat"])
            assertNotNull(config.rules.ruleConfigs["commitMessages"])
            assertNotNull(config.rules.ruleConfigs["jiraTicket"])
            assertNotNull(config.rules.ruleConfigs["crossEntityConsistency"])
            assertNotNull(config.rules.ruleConfigs["draftPrevention"])
            assertNotNull(config.rules.ruleConfigs["labels"])
            assertNotNull(config.rules.ruleConfigs["sizeLimits"])
            assertNotNull(config.rules.ruleConfigs["languagePolicy"])
        }
    }

    @Test
    fun `base config yaml parses reporters and comment-posters`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            assertNotNull(config.platforms?.get("gitlab"))
            assertNotNull(config.reporters?.get("markdown"))
            assertTrue(isModuleDisabled(config.reporters?.get("console")))
        }
    }

    @Test
    fun `base config yaml parses providers block`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            assertNotNull(config.platforms, "platforms block should be parsed")
            assertTrue(config.platforms!!.containsKey("gitlab"), "gitlab platform should exist")
            assertEquals(
                "https://gitlab.example.com",
                ((config.platforms!!["gitlab"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    @Test
    fun `title-format rule has correct pattern and length bounds`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<TitleFormatConfig>(config.rules.ruleConfigs["titleFormat"]!!)

            assertEquals("^(\\[[A-Z]+-\\d+\\] )?.+", typed.pattern)
            assertEquals(10, typed.minLength)
            assertEquals(120, typed.maxLength)
        }
    }

    @Test
    fun `commit-messages rule has correct pattern and max length`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<CommitMessagesConfig>(config.rules.ruleConfigs["commitMessages"]!!)

            assertEquals("^(\\[[A-Z]+-\\d+\\] )?.+", typed.pattern)
            assertEquals(72, typed.maxLength)
        }
    }

    @Test
    fun `jira-ticket rule requires PROJ project key`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<JiraTicketConfig>(config.rules.ruleConfigs["jiraTicket"]!!)

            assertEquals(listOf("PROJ"), typed.projectKeys)
            val whenExpr = assertNotNull(
                config.rules
                    .metaFor("jiraTicket")
                    .`when`
                    ?.expression
            )
            assertTrue(whenExpr.contains("title_matches("))
        }
    }

    @Test
    fun `size-limits rule has correct thresholds`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<SizeLimitsConfig>(config.rules.ruleConfigs["sizeLimits"]!!)

            assertEquals(75, typed.maxFiles)
            assertEquals(2000, typed.maxLines)
            assertEquals(30, typed.maxCommits)
        }
    }

    @Test
    fun `commit-body rule is not configured globally`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            assertTrue(config.rules.ruleConfigs["commitBody"] == null)
        }
    }

    @Test
    fun `policy defines commit-body rule with fullMatch and changelog pattern`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)

            val jiraPolicy = policies
                .map { it.jsonObject }
                .firstOrNull { it["when"]?.jsonPrimitive?.content?.contains("has_ticket") == true }
            assertNotNull(jiraPolicy)

            val override = jiraPolicy["override"]?.jsonObject
            assertNotNull(override)
            val commitBody = override["commit-body"]?.jsonObject
            assertNotNull(commitBody)
            assertEquals("fullMatch", commitBody["matchMode"]?.jsonPrimitive?.content)
            assertEquals(true, commitBody["required"]?.jsonPrimitive?.boolean)
            assertTrue(commitBody["pattern"]?.jsonPrimitive?.content?.contains("CHANGELOG") == true)
        }
    }

    @Test
    fun `policies block is present and contains one entry`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            assertEquals(1, policies.size)
        }
    }

    @Test
    fun `language-policy requires latin script only`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<LanguagePolicyConfig>(config.rules.ruleConfigs["languagePolicy"]!!)

            assertEquals(listOf("latin"), typed.allowedScripts)
            assertTrue(typed.checkMrTitle)
            assertTrue(typed.checkCommitTitle)
            assertTrue(typed.checkBranchName)
        }
    }

    @Test
    fun `language-policy checkMrDescription is false`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
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

class BaseAiConfigTest {
    private suspend fun aiBlock(): JsonObject {
        val root = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML).jsonObject
        return root["ai"]?.jsonObject ?: error("ai block missing")
    }

    @Test
    fun `ai block is present in parsed config`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            assertNotNull(config.ai, "ai block should be parsed")
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
    fun `ai block has response-language and strictness`() {
        runTest {
            val ai = aiBlock()
            assertEquals("English", ai["response-language"]?.jsonPrimitive?.content)
            assertEquals("standard", ai["strictness"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `ai streams replies and adds cross-file context`() {
        runTest {
            val ai = aiBlock()
            assertEquals("true", ai["stream"]?.jsonPrimitive?.content)
            assertEquals(
                "true",
                ai["cross-file-context"]
                    ?.jsonObject
                    ?.get("enabled")
                    ?.jsonPrimitive
                    ?.content
            )
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

    @Test
    fun `ai code-suggestions is enabled with inline mode and warning min-severity`() {
        runTest {
            val ai = aiBlock()
            val codeSuggestions = ai["code-suggestions"]?.jsonObject
            assertNotNull(codeSuggestions, "code-suggestions block missing")
            assertEquals(true, codeSuggestions["enabled"]?.jsonPrimitive?.boolean)
            assertEquals("inline", codeSuggestions["mode"]?.jsonPrimitive?.content)
            assertEquals("warning", codeSuggestions["min-severity"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `ai cost-budget limits tokens and defines exceeded action`() {
        runTest {
            val ai = aiBlock()
            val budget = ai["cost-budget"]?.jsonObject
            assertNotNull(budget, "cost-budget block missing")
            assertEquals(20000, budget["max-tokens-per-run"]?.jsonPrimitive?.content?.toInt())
            assertEquals("skip-remaining", budget["on-budget-exceeded"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `ai grounding block is present with verification options`() {
        runTest {
            val ai = aiBlock()
            val grounding = ai["grounding"]?.jsonObject
            assertNotNull(grounding, "grounding block missing")
            assertEquals(true, grounding["check-symbol-existence"]?.jsonPrimitive?.boolean)
            assertEquals(true, grounding["check-code-snippets"]?.jsonPrimitive?.boolean)
            assertEquals(true, grounding["auto-downgrade-refuted"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun `ai filter has correct thresholds`() {
        runTest {
            val ai = aiBlock()
            val filter = ai["filter"]?.jsonObject
            assertNotNull(filter, "filter block missing")
            assertEquals(10, filter["max-per-rule"]?.jsonPrimitive?.content?.toInt())
            assertEquals(5, filter["max-per-file"]?.jsonPrimitive?.content?.toInt())
            assertEquals(true, filter["deduplicate"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun `ai size-policy has skip exclude and partial-review`() {
        runTest {
            val ai = aiBlock()
            val sizePolicy = ai["size-policy"]?.jsonObject
            assertNotNull(sizePolicy, "size-policy block missing")

            val skip = sizePolicy["skip"]?.jsonObject
            assertNotNull(skip)
            assertEquals(500, skip["maxFiles"]?.jsonPrimitive?.content?.toInt())

            val exclude = sizePolicy["exclude"]?.jsonObject
            assertNotNull(exclude)
            val patterns = exclude["patterns"]?.jsonArray
            assertNotNull(patterns)
            assertTrue(patterns.any { it.jsonPrimitive.content.contains("generated") })

            val partial = sizePolicy["partial-review"]?.jsonObject
            assertNotNull(partial)
            assertEquals(40, partial["maxFiles"]?.jsonPrimitive?.content?.toInt())
        }
    }

    @Test
    fun `ai diff-preprocessor skips renames and binary diffs`() {
        runTest {
            val ai = aiBlock()
            val preprocessor = ai["size-policy"]?.jsonObject?.get("diff-preprocessor")?.jsonObject
            assertNotNull(preprocessor, "diff-preprocessor block missing")
            assertEquals(true, preprocessor["skip-deleted-files"]?.jsonPrimitive?.boolean)
            assertEquals(true, preprocessor["skip-pure-renames"]?.jsonPrimitive?.boolean)
            assertEquals(true, preprocessor["strip-diff-metadata"]?.jsonPrimitive?.boolean)
            assertEquals(true, preprocessor["skip-binary-diffs"]?.jsonPrimitive?.boolean)
        }
    }

    @Test
    fun `ai scopes defines android and ci`() {
        runTest {
            val ai = aiBlock()
            val scopes = ai["scopes"]?.jsonObject
            assertNotNull(scopes, "scopes block missing")
            assertNotNull(scopes["android"]?.jsonObject, "android scope missing")
            assertNotNull(scopes["ci"]?.jsonObject, "ci scope missing")
        }
    }
}

// =========================================================================
// CACHE CONFIG
// =========================================================================

class BaseCacheConfigTest {
    private suspend fun parseBaseConfig() = ConfigMapParser.parseFullConfig(YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)).config

    @Test
    fun `cache block is present in parsed config`() {
        runTest {
            val config = parseBaseConfig()
            assertNotNull(config.cache, "cache block should be parsed")
        }
    }

    @Test
    fun `cache block is disabled`() {
        runTest {
            val config = parseBaseConfig()
            assertFalse(config.cache!!.enabled, "cache should be disabled")
        }
    }
}

// =========================================================================
// SUGGESTION / EXPLANATION
// =========================================================================

class BaseSuggestionExplanationTest {
    private suspend fun parseBaseConfig() = ConfigMapParser.parseFullConfig(YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)).config

    @Test
    fun `title-format meta contains suggestion with format examples`() {
        runTest {
            val meta = parseBaseConfig().rules.metaFor("titleFormat")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("[PROJ-123]"))
            assertTrue(meta.suggestion!!.contains("[TEL-42]"))
            assertTrue(meta.suggestion!!.contains("10-120"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("searchable"))
        }
    }

    @Test
    fun `commit-messages meta contains suggestion with format and limits`() {
        runTest {
            val meta = parseBaseConfig().rules.metaFor("commitMessages")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("[PROJ-123]"))
            assertTrue(meta.suggestion!!.contains("72"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("git log"))
        }
    }

    @Test
    fun `jira-ticket meta contains suggestion with project key info`() {
        runTest {
            val meta = parseBaseConfig().rules.metaFor("jiraTicket")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("JIRA key"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("audit trails"))
        }
    }

    @Test
    fun `policy commit-body contains suggestion and explanation`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            val override = policies[0].jsonObject["override"]?.jsonObject
            assertNotNull(override)
            val commitBody = override["commit-body"]?.jsonObject
            assertNotNull(commitBody)
            assertTrue(commitBody["suggestion"]?.jsonPrimitive?.content?.contains("CHANGELOG") == true)
            assertTrue(commitBody["explanation"]?.jsonPrimitive?.content?.contains("release notes") == true)
        }
    }

    @Test
    fun `cross-entity-consistency meta contains suggestion with example`() {
        runTest {
            val meta = parseBaseConfig().rules.metaFor("crossEntityConsistency")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("PROJ-999"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("JIRA links"))
        }
    }

    @Test
    fun `draft-prevention meta contains suggestion with removal instructions`() {
        runTest {
            val meta = parseBaseConfig().rules.metaFor("draftPrevention")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("Mark as ready"))
            assertTrue(meta.suggestion!!.contains("WIP"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("main branch"))
        }
    }

    @Test
    fun `size-limits meta contains suggestion about splitting MRs`() {
        runTest {
            val meta = parseBaseConfig().rules.metaFor("sizeLimits")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("Split"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("review"))
        }
    }

    @Test
    fun `language-policy meta contains suggestion with checked fields`() {
        runTest {
            val meta = parseBaseConfig().rules.metaFor("languagePolicy")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("non-Latin"))
            assertTrue(meta.suggestion!!.contains("MR title"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("team members"))
        }
    }
}

// =========================================================================
// END-TO-END
// =========================================================================

class BaseConfigE2ETest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = TestFiles.createTestTempDirectory("base-e2e")
    }

    @AfterTest
    fun cleanup() {
        TestFiles.deleteRecursively(tempDir)
    }

    private var fileCounter = 0

    private fun configFile(): Path {
        val f = Path(tempDir, "config-${fileCounter++}.yaml")
        writeBaseTestFile(f, BASE_CONFIG_YAML.replace("\nai:\n", "\nai:\n  statistics:\n    dir: \"$tempDir/stats\"\n"))
        return f
    }

    private fun mrFile(json: String): Path {
        val f = Path(tempDir, "mr-${fileCounter++}.json")
        writeBaseTestFile(f, json)
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
    fun `valid android mr passes`() =
        runTest {
            val mr = mrFile(androidMr("[PROJ-123] Add login screen"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `valid android mr without task id passes`() =
        runTest {
            val mr = mrFile(androidMrNoChangelog("Fix NPE in login flow"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `android mr with jira and changelog passes`() =
        runTest {
            val mr = mrFile(androidMr("[PROJ-456] Fix navigation crash"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `draft mr fails draft prevention`() =
        runTest {
            val mr = mrFile(draftMr("[PROJ-123] WIP login screen"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr with cyrillic text in title fails language policy`() =
        runTest {
            val mr = mrFile(androidMr("[PROJ-123] \u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u044d\u043a\u0440\u0430\u043d"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `commit with extra task id not in mr title fails cross entity check`() =
        runTest {
            val mr = mrFile(crossEntityViolation())
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `android mr with jira but no changelog body fails`() =
        runTest {
            val mr = mrFile(androidMrNoChangelog("[PROJ-456] Fix navigation crash"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr title too short fails title format`() =
        runTest {
            val mr = mrFile(androidMr("Hi there"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    // ---- MR JSON builders ---------------------------------------------------

    private fun androidMr(title: String): String {
        val taskId = Regex("\\[([A-Z]+-\\d+)\\]").find(title)?.groupValues?.get(1) ?: "PROJ-123"
        return """
            {
                "id": "1",
                "iid": 100,
                "title": "$title",
                "description": "Implementation details",
                "source_branch": "feature/$taskId-login",
                "target_branch": "main",
                "author": { "username": "john", "name": "John Doe" },
                "state": "opened",
                "draft": false,
                "labels": [],
                "commits": [
                    {
                        "sha": "abc123",
                        "message": "$title\n\nCHANGELOG: [$taskId] Add login screen.",
                        "author": { "name": "John", "email": "john@example.com" },
                        "timestamp": "2024-01-15T10:30:00Z"
                    }
                ],
                "changes": []
            }
            """.trimIndent()
    }

    private fun androidMrNoChangelog(title: String) =
        """
        {
            "id": "2",
            "iid": 200,
            "title": "$title",
            "description": "Implementation details",
            "source_branch": "feature/fix-npe",
            "target_branch": "main",
            "author": { "username": "jane", "name": "Jane Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "def456",
                    "message": "$title",
                    "author": { "name": "Jane", "email": "jane@example.com" },
                    "timestamp": "2024-01-15T11:00:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun draftMr(title: String) =
        """
        {
            "id": "3",
            "iid": 300,
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
                    "message": "$title\n\nCHANGELOG: [PROJ-123] WIP.",
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
            "id": "4",
            "iid": 400,
            "title": "[PROJ-123] Single task MR",
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
                    "message": "[PROJ-123] [PROJ-999] Work referencing extra task\n\nCHANGELOG: [PROJ-123] Single task.",
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

class BasePoliciesMigrationTest {
    @Test
    fun `base config with policies block parses successfully`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            assertEquals(8, config.rules.ruleConfigs.size, "rules section must have 8 entries")
        }
    }

    @Test
    fun `policies block override contains commit-body rule`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(BASE_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            val override = policies[0].jsonObject["override"]?.jsonObject
            assertNotNull(override)
            assertTrue(override.containsKey("commit-body"), "override must contain commit-body rule")
        }
    }
}

// =========================================================================
// PROMPT SOURCE REGRESSION
// =========================================================================

class BasePromptSourceRegressionTest {
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
            assertNotNull(reviewCommentsPrompt, "ai.prompts.review-comments must be present")

            val source = PromptSource.fromJson(reviewCommentsPrompt)
            assertIs<PromptSource.Layered>(source)
            assertEquals(true, source.includeBuiltin)
            assertEquals(1, source.sources.size)
            assertIs<PromptSource.File>(source.sources[0])
            assertEquals(".karandash/review-prompt.md", (source.sources[0] as PromptSource.File).path)
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
