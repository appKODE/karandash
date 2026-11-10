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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun writeProjectTestFile(
    path: Path,
    content: String
) {
    SystemFileSystem.sink(path).buffered().use { sink ->
        sink.write(content.encodeToByteArray())
        sink.flush()
    }
}

/**
 * A fixture shaped after the project configs (base + cbx overrides), with env vars replaced by
 * test-safe values. It is not the effective config: ProjectRealConfigChainTest loads the real chain.
 */
private val PROJECT_CONFIG_YAML = """
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
    suggestion: "Format: '[${ProjectSpec.KEY}-123] Title' or just 'Title'. Length: 10-120 chars."
    explanation: "Consistent MR titles make git history searchable by task ID and enable automated changelog generation."
  commit-messages:
    pattern: "^(\\[[A-Z]+-\\d+\\] )?.+"
    max-length: 72
    bail-on-fail: true
    suggestion: "Format: '[${ProjectSpec.KEY}-123] Description' or just 'Description'. Max 72 chars."
    explanation: "Consistent commit messages improve git log readability and enable per-task release notes."
  jira-ticket:
    when: 'title_matches("\[[A-Z]+-\d+\]")'
    projectKeys: ["${ProjectSpec.KEY}"]
    suggestion: "Use ${ProjectSpec.KEY} JIRA key in the ticket reference."
    explanation: "JIRA references link code changes to project requirements for audit trails and sprint tracking."
  cross-entity-consistency:
    taskIdPattern: "${ProjectSpec.KEY}-\\d+"
    commitTasksSubsetOfMr: true
    changelogConsistency: true
    changelogPrefix: "CHANGELOG:"
    mrTaskSource: "title"
    changelogDuplicateRequiresMatchingText: true
    suggestion: "All task IDs in commits must also appear in the MR title."
    explanation: "Orphaned task references cause broken JIRA links and inaccurate sprint reports."
  draft-prevention:
    enabled: true
    suggestion: "In GitLab, click 'Mark as ready'. Remove 'WIP:', '[WIP]', or 'Draft:' from the title if present."
    explanation: "Merging incomplete work can break the main branch and block other developers."
  labels:
    requiredLabels: []
    suggestion: "No labels required currently."
    explanation: "Labels categorize MRs for filtering, metrics, and automated workflow routing."
  size-limits:
    maxFiles: 75
    maxLines: 2000
    maxCommits: 30
    suggestion: "Split large MRs into smaller focused ones."
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
  - when: 'title_matches("\[[A-Z]+-\d+\]")'
    override:
      commit-body:
        pattern: "^CHANGELOG: (\\[${ProjectSpec.KEY}-\\d+\\] .+)(, \\[${ProjectSpec.KEY}-\\d+\\] .+)*\\.${'$'}"
        required: true
        matchMode: "fullMatch"
        suggestion: "Add CHANGELOG line: 'CHANGELOG: [${ProjectSpec.KEY}-123] Description.' (description can be in Russian)"
        explanation: "CHANGELOG lines enable per-platform release notes generation."

cache:
  enabled: false

ai:
  provider: "bifrost"
  model: "openrouter/moonshotai/kimi-k2.5"
  api-key: "test-bifrost-key"
  base-url: "https://bifrost.example.com"
  max-token-budget: ${ProjectSpec.MAX_TOKENS}
  fail-on-error: false
  timeout: "5m"
  execution-timeout: "6m"
  max-retries: 1
  priority: 200
  mode: supplement
  response-language: "English"
  strictness: "standard"
  risk-scoring:
    enabled: true
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
    max-tokens-per-run: ${ProjectSpec.MAX_TOKENS}
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

class ProjectYamlConfigParsingTest {
    @Test
    fun `project config contains all expected rule entries`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            assertNotNull(config.rules.ruleConfigs["titleFormat"])
            assertNotNull(config.rules.ruleConfigs["commitMessages"])
            assertNotNull(config.rules.ruleConfigs["jiraTicket"])
            assertNotNull(config.rules.ruleConfigs["crossEntityConsistency"])
            assertNotNull(config.rules.ruleConfigs["draftPrevention"])
            assertNotNull(config.rules.ruleConfigs["sizeLimits"])
            assertNotNull(config.rules.ruleConfigs["languagePolicy"])
        }
    }

    @Test
    fun `jira-ticket rule requires project project key`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<JiraTicketConfig>(config.rules.ruleConfigs["jiraTicket"]!!)

            assertEquals(listOf(ProjectSpec.KEY), typed.projectKeys)
        }
    }

    @Test
    fun `cross-entity-consistency uses project task pattern`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<CrossEntityConsistencyConfig>(config.rules.ruleConfigs["crossEntityConsistency"]!!)

            assertEquals("${ProjectSpec.KEY}-\\d+", typed.taskIdPattern)
        }
    }

    @Test
    fun `policies block has project changelog pattern`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)

            val commitBody = policies[0]
                .jsonObject["override"]
                ?.jsonObject
                ?.get("commit-body")
                ?.jsonObject
            assertNotNull(commitBody)
            assertTrue(commitBody["pattern"]?.jsonPrimitive?.content?.contains("${ProjectSpec.KEY}-") == true)
        }
    }

    @Test
    fun `reporters and comment-posters parsed`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            assertNotNull(config.platforms?.get("gitlab"))
            assertNotNull(config.reporters?.get("markdown"))
            assertTrue(isModuleDisabled(config.reporters?.get("console")))
        }
    }
}

// =========================================================================
// AI CONFIG
// =========================================================================

class ProjectAiConfigTest {
    private suspend fun aiBlock(): JsonObject {
        val root = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML).jsonObject
        return root["ai"]?.jsonObject ?: error("ai block missing")
    }

    @Test
    fun `ai block is present`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            assertNotNull(config.ai)
        }
    }

    @Test
    fun `ai cost-budget uses project token limit`() {
        runTest {
            val ai = aiBlock()
            val budget = ai["cost-budget"]?.jsonObject
            assertNotNull(budget)
            assertEquals(ProjectSpec.MAX_TOKENS, budget["max-tokens-per-run"]?.jsonPrimitive?.content?.toInt())
        }
    }

    @Test
    fun `the fixture names no model the real base config lacks`() {
        val root = checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" }
        val base = TestFiles.readText(Path(root, "projects/base/.karandash/config.yaml"))
        val models = Regex("""openrouter/[\w./-]+""").findAll(PROJECT_CONFIG_YAML).map { it.value }.toSet()
        assertEquals(emptySet(), models.filterNot { it in base }.toSet())
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

class ProjectCacheConfigTest {
    @Test
    fun `cache block is disabled`() {
        runTest {
            val config = ConfigMapParser
                .parseFullConfig(
                    YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML),
                ).config
            assertNotNull(config.cache)
            assertFalse(config.cache!!.enabled)
        }
    }
}

// =========================================================================
// SUGGESTION / EXPLANATION
// =========================================================================

class ProjectSuggestionExplanationTest {
    private suspend fun parseConfig() = ConfigMapParser.parseFullConfig(YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML)).config

    @Test
    fun `title-format meta contains suggestion with project examples`() {
        runTest {
            val meta = parseConfig().rules.metaFor("titleFormat")
            assertNotNull(meta.suggestion)
            assertTrue(checkNotNull(meta.suggestion) { "no suggestion" }.contains("[${ProjectSpec.KEY}-123]"))
        }
    }

    @Test
    fun `jira-ticket meta mentions project key`() {
        runTest {
            val meta = parseConfig().rules.metaFor("jiraTicket")
            assertNotNull(meta.suggestion)
            assertTrue(checkNotNull(meta.suggestion) { "no suggestion" }.contains(ProjectSpec.KEY))
        }
    }

    @Test
    fun `draft-prevention meta contains suggestion`() {
        runTest {
            val meta = parseConfig().rules.metaFor("draftPrevention")
            assertNotNull(meta.suggestion)
            assertTrue(checkNotNull(meta.suggestion) { "no suggestion" }.contains("Mark as ready"))
        }
    }

    @Test
    fun `language-policy meta contains suggestion`() {
        runTest {
            val meta = parseConfig().rules.metaFor("languagePolicy")
            assertNotNull(meta.suggestion)
            assertTrue(checkNotNull(meta.suggestion) { "no suggestion" }.contains("non-Latin"))
        }
    }
}

// =========================================================================
// END-TO-END
// =========================================================================

class ProjectConfigE2ETest {
    private lateinit var tempDir: Path
    private var fileCounter = 0

    @BeforeTest
    fun setup() {
        tempDir = TestFiles.createTestTempDirectory("project-e2e")
    }

    @AfterTest
    fun cleanup() {
        TestFiles.deleteRecursively(tempDir)
    }

    private fun configFile(): Path {
        val f = Path(tempDir, "config-${fileCounter++}.yaml")
        writeProjectTestFile(f, PROJECT_CONFIG_YAML.replace("\nai:\n", "\nai:\n  statistics:\n    dir: \"$tempDir/stats\"\n"))
        return f
    }

    private fun mrFile(json: String): Path {
        val f = Path(tempDir, "mr-${fileCounter++}.json")
        writeProjectTestFile(f, json)
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
            val mr = mrFile(androidMr("[${ProjectSpec.KEY}-100] Add wallet balance screen"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `draft mr fails`() =
        runTest {
            val mr = mrFile(draftMr("[${ProjectSpec.KEY}-100] WIP wallet balance"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr with cyrillic title fails`() =
        runTest {
            val mr =
                mrFile(
                    androidMr(
                        "[${ProjectSpec.KEY}-100] \u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u0431\u0430\u043b\u0430\u043d\u0441"
                    )
                )
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `commit with extra task id fails cross entity check`() =
        runTest {
            val mr = mrFile(crossEntityViolation())
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `android mr with jira but no changelog fails`() =
        runTest {
            val mr = mrFile(androidMrNoChangelog("[${ProjectSpec.KEY}-100] Fix payment crash"))
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
            "source_branch": "feature/${ProjectSpec.KEY}-100-wallet",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "$title\n\nCHANGELOG: [${ProjectSpec.KEY}-100] Add wallet balance screen.",
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
            "source_branch": "feature/${ProjectSpec.KEY}-100-fix",
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
            "source_branch": "feature/${ProjectSpec.KEY}-100-wip",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": true,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "$title\n\nCHANGELOG: [${ProjectSpec.KEY}-100] WIP.",
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
            "title": "[${ProjectSpec.KEY}-100] Single task MR",
            "description": "Implementation",
            "source_branch": "feature/${ProjectSpec.KEY}-100",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "[${ProjectSpec.KEY}-100] [${ProjectSpec.KEY}-999] Work referencing extra task\n\nCHANGELOG: [${ProjectSpec.KEY}-100] Single task.",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()
}

// =========================================================================
// POLICIES MIGRATION
// =========================================================================

class ProjectPoliciesMigrationTest {
    @Test
    fun `policies block is present and contains one project policy`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            assertEquals(1, policies.size)
            val policy = policies[0].jsonObject
            assertNotNull(policy["when"])
            assertNotNull(policy["override"])
        }
    }

    @Test
    fun `policies override contains commit-body rule`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            val override = policies!![0].jsonObject["override"]?.jsonObject
            assertNotNull(override)
            assertTrue(override!!.containsKey("commit-body"))
        }
    }
}

// =========================================================================
// PROMPT SOURCE REGRESSION
// =========================================================================

class ProjectPromptSourceRegressionTest {
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

// =========================================================================
// BASE CONFIG INHERITANCE
// =========================================================================

class ProjectBaseConfigTest {
    @Test
    fun `title-format has base pattern with space`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed =
                deserializeConfig<TitleFormatConfig>(checkNotNull(config.rules.ruleConfigs["titleFormat"]) { "no titleFormat config" })

            assertEquals("^(\\[[A-Z]+-\\d+\\] )?.+", typed.pattern)
        }
    }

    @Test
    fun `platforms block parses gitlab`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(PROJECT_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            val gitlab = config.platforms?.get("gitlab") as? JsonObject
            assertNotNull(gitlab)
            assertEquals("https://gitlab.example.com", (gitlab["url"] as? JsonPrimitive)?.contentOrNull)
        }
    }
}
