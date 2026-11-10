package io.karandash.validators

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.config.pipeline.isModuleDisabled
import io.karandash.duration.KarandashDuration
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

/**
 * Parses the embedded NAV config YAML to a temp file with a unique suffix.
 */
private fun writeMapsTestFile(
    path: Path,
    content: String
) {
    SystemFileSystem.sink(path).buffered().use { sink ->
        sink.write(content.encodeToByteArray())
        sink.flush()
    }
}

/**
 * The actual NAV project karandash config as it lives in .karandash/config.yaml.
 * Embedded here so parsing tests can verify the config is correctly structured.
 * Env var references are replaced with test-safe placeholder strings.
 */
private val NAV_CONFIG_YAML = """
platforms:
  gitlab:
    url: "https://gitlab.example.com"
    token: "test-token"


reporters:
  markdown: true
  console: false

rules:
  title-format:
    pattern: "^((\\[NAV-\\d+\\])+)?(\\[(ios|android|core)\\]) .+"
    min-length: 10
    max-length: 120
    suggestion: "Examples: '[ios] Add login screen', '[NAV-123][ios] Add login screen', '[android] Fix map rendering', '[NAV-456][android] Fix map rendering', '[core] Update shared models'. For multiple tasks: '[NAV-123][NAV-456][ios] Title'. Length: 10-120 chars."
    explanation: "Consistent MR titles make git history searchable by task ID and platform."
  commit-messages:
    pattern: "^((\\[NAV-\\d+\\])+)?(\\[(ios|android|core)\\]) .+"
    min-length: 10
    max-length: 72
    suggestion: "Examples: '[ios] Implement login UI', '[NAV-123][ios] Implement login UI', '[android] Fix null pointer in MapView', '[NAV-456][android] Fix null pointer in MapView', '[core] Add shared data model'. Length: 10-72 chars."
    explanation: "Uniform commit messages improve git log readability and enable automated release notes per platform."
  jira-ticket:
    when: 'title_matches("\[[A-Z]+-\d+\]")'
    projectKeys: ["NAV"]
    suggestion: "NAV ticket in title is optional. If present, it must use the NAV key (e.g. '[NAV-123][ios] Add feature'). Other keys (PROJ, TEAM, etc.) are rejected."
    explanation: "JIRA references link code changes to project requirements for audit trails and sprint tracking."
  context-tag:
    allowed: ["ios", "android", "core"]
    exactlyOne: true
    checkMrTitle: true
    checkCommitTitles: true
    tagPattern: "\\[([a-zA-Z][a-zA-Z0-9_]*)\\]"
    caseSensitive: true
    suggestion: "Add exactly one tag: [ios] for iOS, [android] for Android, or [core] for shared code. Place it after the task ID in both MR title and each commit."
    explanation: "Context tags route MRs to platform-specific reviewers and feed per-platform metrics dashboards."
  cross-entity-consistency:
    taskIdPattern: "NAV-\\d+"
    commitTasksSubsetOfMr: true
    changelogConsistency: true
    changelogPrefix: "CHANGELOG:"
    mrTaskSource: "title"
    changelogDuplicateRequiresMatchingText: true
    suggestion: "Remove extra task IDs from commits that are not in the MR title, or add them to the MR title. Example: MR '[NAV-123][ios] Feature' should not have commits referencing NAV-456."
    explanation: "Orphaned task references cause broken JIRA links and inaccurate sprint reports."
  draft-prevention:
    suggestion: "In GitLab, click 'Mark as ready'. Remove 'WIP:', '[WIP]', or 'Draft:' from the title if present."
    explanation: "Merging incomplete work can break the main branch and block other developers."
  labels:
    requiredLabels: []
    suggestion: "No labels required currently. When labels are configured, apply them via the MR sidebar in GitLab."
    explanation: "Labels categorize MRs for filtering, metrics, and automated workflow routing."
  language-policy:
    checkMrTitle: true
    checkMrDescription: false
    checkCommitTitle: true
    checkCommitDescription: true
    checkBranchName: true
    allowedScripts: ["latin"]
    languageName: "English"
    suggestion: "Replace non-Latin characters with English equivalents in MR title, branch name, commit title and description. Accented Latin (cafe, resume) is allowed."
    explanation: "English-only text ensures all team members can read git-flow information regardless of native language."
policies:
  - when: "has_ticket(NAV) && has_tag(android)"
    override:
      commit-body:
        pattern: "^CHANGELOG: (\\[NAV-\\d+\\] .+)(, \\[NAV-\\d+\\] .+)*\\.${'$'}"
        required: true
        matchMode: "fullMatch"
        suggestion: "Commit body must start with: 'CHANGELOG: [NAV-123] Task name.' (ends with period)."
        explanation: "Structured commit bodies ensure changelog can be generated from commit history."

cache:
  enabled: false

ai:
  provider: "bifrost"
  model: "openrouter/moonshotai/kimi-k2.5"
  api-key: "test-bifrost-key"
  base-url: "https://bifrost.example.com"
  max-token-budget: 30000
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
    max-tokens-per-run: 30000
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
        - "**/package-lock.json"
        - "**/*.md"
        - "**/Pods/**"
        - "**/build/**"
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
      paths: ["android/**", "app/src/**/*.kt", "app/src/**/*.java"]
      instructions: "Android-specific review instructions."
      best-practices: "Android best practices for code review."
    ios:
      paths: ["iphone/**", "**/*.swift", "**/*.m", "**/*.mm"]
      instructions: "iOS-specific review instructions."
      best-practices: "iOS best practices for code review."
    core:
      paths: ["core/**", "**/*.cpp", "**/*.h", "**/*.hpp", "**/*.c"]
      instructions: "Core-specific review instructions."
      best-practices: "Core best practices for code review."
""".trimIndent()

// =========================================================================
// CONFIG PARSING - verifies the NAV YAML is structurally correct and
// all rule configs round-trip through YamlFormatModule → ConfigMapParser
// =========================================================================

class MapsYamlConfigParsingTest {
    @Test
    fun `navigator config yaml contains all 8 expected top-level rule entries`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            assertNotNull(config.rules.ruleConfigs["titleFormat"])
            assertNotNull(config.rules.ruleConfigs["commitMessages"])
            assertNotNull(config.rules.ruleConfigs["jiraTicket"])
            assertNotNull(config.rules.ruleConfigs["contextTag"])
            assertNotNull(config.rules.ruleConfigs["crossEntityConsistency"])
            assertNotNull(config.rules.ruleConfigs["draftPrevention"])
            assertNotNull(config.rules.ruleConfigs["labels"])
            assertNotNull(config.rules.ruleConfigs["languagePolicy"])
        }
    }

    @Test
    fun `navigator config yaml parses reporters and comment-posters`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            assertNotNull(config.platforms?.get("gitlab"))
            assertNotNull(config.reporters?.get("markdown"))
            assertTrue(isModuleDisabled(config.reporters?.get("console")))
        }
    }

    @Test
    fun `navigator config yaml parses providers block`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
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
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<TitleFormatConfig>(config.rules.ruleConfigs["titleFormat"]!!)

            assertEquals("^((\\[NAV-\\d+\\])+)?(\\[(ios|android|core)\\]) .+", typed.pattern)
            assertEquals(10, typed.minLength)
            assertEquals(120, typed.maxLength)
        }
    }

    @Test
    fun `commit-messages rule has correct pattern and length bounds`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<CommitMessagesConfig>(config.rules.ruleConfigs["commitMessages"]!!)

            assertEquals("^((\\[NAV-\\d+\\])+)?(\\[(ios|android|core)\\]) .+", typed.pattern)
            assertEquals(10, typed.minLength)
            assertEquals(72, typed.maxLength)
        }
    }

    @Test
    fun `jira-ticket rule requires NAV project key`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<JiraTicketConfig>(config.rules.ruleConfigs["jiraTicket"]!!)

            assertEquals(listOf("NAV"), typed.projectKeys)
            val whenExpr = assertNotNull(
                config.rules
                    .metaFor("jiraTicket")
                    .`when`
                    ?.expression
            )
            assertTrue(whenExpr.contains("title_matches("))
            assertTrue(whenExpr.contains("[A-Z]+-"))
        }
    }

    @Test
    fun `context-tag rule allows exactly ios android core`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<ContextTagConfig>(config.rules.ruleConfigs["contextTag"]!!)

            assertEquals(listOf("ios", "android", "core"), typed.allowed)
            assertTrue(typed.exactlyOne)
            assertTrue(typed.checkMrTitle)
            assertTrue(typed.checkCommitTitles)
            assertTrue(typed.caseSensitive)
        }
    }

    @Test
    fun `commit-body rule is not configured globally`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            assertTrue(config.rules.ruleConfigs["commitBody"] == null)
        }
    }

    @Test
    fun `android policy defines commit-body rule with fullMatch and changelog pattern`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML).jsonObject
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
            assertTrue(commitBody["pattern"]?.jsonPrimitive?.content?.endsWith(".\$") == true)
            assertFalse(override.containsKey("description-format"))
        }
    }

    @Test
    fun `policies block is present and contains one android policy`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            assertEquals(1, policies.size)
            val policy = policies[0].jsonObject
            assertTrue(policy["when"]?.jsonPrimitive?.content?.contains("android") == true)
        }
    }

    @Test
    fun `language-policy requires latin script only`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
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
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config
            val typed = deserializeConfig<LanguagePolicyConfig>(config.rules.ruleConfigs["languagePolicy"]!!)

            assertFalse(typed.checkMrDescription)
        }
    }
}

// =========================================================================
// AI PERSISTENCE CONFIG - verifies the ai: block with persistence settings
// is correctly parsed from the NAV config YAML
// =========================================================================

class MapsAiConfigTest {
    private suspend fun aiBlock(): JsonObject {
        val root = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML).jsonObject
        return root["ai"]?.jsonObject ?: error("ai block missing")
    }

    @Test
    fun `ai block is present in parsed config`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
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
            assertEquals(30000, budget["max-tokens-per-run"]?.jsonPrimitive?.content?.toInt())
            assertEquals("skip-remaining", budget["on-budget-exceeded"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `ai grounding block is present with verification options`() {
        runTest {
            val ai = aiBlock()
            val grounding = ai["grounding"]?.jsonObject
            assertNotNull(grounding, "grounding block missing — presence implies enabled")
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
            assertEquals(50000, skip["maxTotalLines"]?.jsonPrimitive?.content?.toInt())
            assertEquals(200, skip["maxCommits"]?.jsonPrimitive?.content?.toInt())

            val exclude = sizePolicy["exclude"]?.jsonObject
            assertNotNull(exclude)
            val patterns = exclude["patterns"]?.jsonArray
            assertNotNull(patterns)
            assertTrue(patterns.any { it.jsonPrimitive.content.contains("generated") })
            assertTrue(patterns.any { it.jsonPrimitive.content == "**/*.md" })
            assertTrue(patterns.any { it.jsonPrimitive.content == "**/Pods/**" })

            val partial = sizePolicy["partial-review"]?.jsonObject
            assertNotNull(partial)
            assertEquals(40, partial["maxFiles"]?.jsonPrimitive?.content?.toInt())
            assertEquals("most-changed", partial["strategy"]?.jsonPrimitive?.content)
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
    fun `ai models map has per-rule overrides for review-thread-analysis and mr-summary`() {
        runTest {
            val ai = aiBlock()
            val models = ai["models"]?.jsonObject
            assertNotNull(models, "models block missing")
            assertEquals("openrouter/qwen/qwen3.5-35b-a3b", models["review-thread-analysis"]?.jsonPrimitive?.content)
            assertEquals("openrouter/qwen/qwen3.5-35b-a3b", models["mr-summary"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `ai risk-scoring has model override`() {
        runTest {
            val ai = aiBlock()
            val riskScoring = ai["risk-scoring"]?.jsonObject
            assertNotNull(riskScoring, "risk-scoring block missing")
            assertEquals("openrouter/qwen/qwen3.5-35b-a3b", riskScoring["model"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `ai execution-timeout and max-retries are configured`() {
        runTest {
            val ai = aiBlock()
            assertEquals("6m", ai["execution-timeout"]?.jsonPrimitive?.content)
            assertEquals(1, ai["max-retries"]?.jsonPrimitive?.content?.toInt())
        }
    }

    @Test
    fun `ai priority and mode are configured`() {
        runTest {
            val ai = aiBlock()
            assertEquals(200, ai["priority"]?.jsonPrimitive?.content?.toInt())
            assertEquals("supplement", ai["mode"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `ai extra-instructions is configured`() {
        runTest {
            val ai = aiBlock()
            val src = ai["extra-instructions"]?.jsonPrimitive?.content
            assertNotNull(src, "extra-instructions missing")
            assertTrue(src.isNotBlank())
        }
    }

    @Test
    fun `ai scopes defines android ios core platforms`() {
        runTest {
            val ai = aiBlock()
            val scopes = ai["scopes"]?.jsonObject
            assertNotNull(scopes, "scopes block missing")
            assertNotNull(scopes["android"]?.jsonObject, "android scope missing")
            assertNotNull(scopes["ios"]?.jsonObject, "ios scope missing")
            assertNotNull(scopes["core"]?.jsonObject, "core scope missing")
            val androidPaths = scopes["android"]?.jsonObject?.get("paths")?.jsonArray
            assertNotNull(androidPaths)
            assertTrue(androidPaths.any { it.jsonPrimitive.content == "android/**" })
        }
    }
}

// =========================================================================
// STALENESS GATE CONFIG - verifies the cache block is parsed
// =========================================================================

class MapsCacheConfigTest {
    private suspend fun parseMapsConfig() = ConfigMapParser.parseFullConfig(YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)).config

    @Test
    fun `cache block is present in parsed config`() {
        runTest {
            val config = parseMapsConfig()
            assertNotNull(config.cache, "cache block should be parsed")
        }
    }

    @Test
    fun `cache block is disabled`() {
        runTest {
            val config = parseMapsConfig()
            assertFalse(config.cache!!.enabled, "cache should be disabled")
        }
    }

    @Test
    fun `cache block with dir parses dir field`() {
        runTest {
            val yaml = """
                rules:
                  title-format:
                    pattern: "^feat:"
                cache:
                  dir: "/tmp/test-cache"
            """.trimIndent()
            val config = ConfigMapParser.parseFullConfig(YamlFormatModule().parseToJsonElement(yaml)).config
            assertNotNull(config.cache)
            assertEquals("/tmp/test-cache", config.cache!!.dir)
        }
    }

    @Test
    fun `state cache uses default ttl when not specified`() {
        runTest {
            val yaml = """
                rules:
                  title-format:
                    pattern: "^feat:"
                cache:
                  state: {}
            """.trimIndent()
            val config = ConfigMapParser.parseFullConfig(YamlFormatModule().parseToJsonElement(yaml)).config
            assertNotNull(config.cache?.state)
            assertEquals(KarandashDuration.ofSeconds(604_800), config.cache!!.state.ttl)
        }
    }
}

// =========================================================================
// SUGGESTION / EXPLANATION - verifies that user-configured suggestion and
// explanation fields are parsed from config and applied to findings
// =========================================================================

class MapsSuggestionExplanationTest {
    private suspend fun parseMapsConfig() = ConfigMapParser.parseFullConfig(YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)).config

    @Test
    fun `title-format meta contains suggestion with all platform examples`() {
        runTest {
            val meta = parseMapsConfig().rules.metaFor("titleFormat")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("[NAV-123][ios]"))
            assertTrue(meta.suggestion!!.contains("[ios]"))
            assertTrue(meta.suggestion!!.contains("[android]"))
            assertTrue(meta.suggestion!!.contains("[core]"))
            assertTrue(meta.suggestion!!.contains("10-120"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("searchable"))
        }
    }

    @Test
    fun `commit-messages meta contains suggestion with format and limits`() {
        runTest {
            val meta = parseMapsConfig().rules.metaFor("commitMessages")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("[NAV-123][ios]"))
            assertTrue(meta.suggestion!!.contains("[ios]"))
            assertTrue(meta.suggestion!!.contains("[android]"))
            assertTrue(meta.suggestion!!.contains("[core]"))
            assertTrue(meta.suggestion!!.contains("10-72"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("git log"))
        }
    }

    @Test
    fun `jira-ticket meta contains suggestion with NAV key and rejection info`() {
        runTest {
            val meta = parseMapsConfig().rules.metaFor("jiraTicket")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("optional"))
            assertTrue(meta.suggestion!!.contains("NAV"))
            assertTrue(meta.suggestion!!.contains("[NAV-123]"))
            assertTrue(meta.suggestion!!.contains("PROJ"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("audit trails"))
        }
    }

    @Test
    fun `context-tag meta contains suggestion with all allowed tags`() {
        runTest {
            val meta = parseMapsConfig().rules.metaFor("contextTag")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("[ios]"))
            assertTrue(meta.suggestion!!.contains("[android]"))
            assertTrue(meta.suggestion!!.contains("[core]"))
            assertTrue(meta.suggestion!!.contains("exactly one"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("reviewers"))
        }
    }

    @Test
    fun `android policy commit-body contains dedicated suggestion and explanation`() {
        runTest {
            val root = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML).jsonObject
            val policies = root["policies"]?.jsonArray
            assertNotNull(policies)
            val androidPolicy = policies
                .map { it.jsonObject }
                .first { it["when"]?.jsonPrimitive?.content?.contains("android") == true }
            val override = androidPolicy["override"]?.jsonObject
            assertNotNull(override)
            val commitBody = override["commit-body"]?.jsonObject
            assertNotNull(commitBody)
            assertTrue(commitBody["suggestion"]?.jsonPrimitive?.content?.contains("Commit body") == true)
            assertTrue(commitBody["suggestion"]?.jsonPrimitive?.content?.contains("CHANGELOG") == true)
            assertTrue(commitBody["explanation"]?.jsonPrimitive?.content?.contains("changelog") == true)
        }
    }

    @Test
    fun `cross-entity-consistency meta contains suggestion with example`() {
        runTest {
            val meta = parseMapsConfig().rules.metaFor("crossEntityConsistency")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("NAV-123"))
            assertTrue(meta.suggestion!!.contains("NAV-456"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("JIRA links"))
        }
    }

    @Test
    fun `draft-prevention meta contains suggestion with removal instructions`() {
        runTest {
            val meta = parseMapsConfig().rules.metaFor("draftPrevention")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("Mark as ready"))
            assertTrue(meta.suggestion!!.contains("WIP"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("main branch"))
        }
    }

    @Test
    fun `labels meta contains suggestion explaining current state`() {
        runTest {
            val meta = parseMapsConfig().rules.metaFor("labels")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("No labels required"))
            assertTrue(meta.suggestion!!.contains("MR sidebar"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("categorize"))
        }
    }

    @Test
    fun `language-policy meta contains suggestion with checked fields`() {
        runTest {
            val meta = parseMapsConfig().rules.metaFor("languagePolicy")
            assertNotNull(meta.suggestion)
            assertTrue(meta.suggestion!!.contains("non-Latin"))
            assertTrue(meta.suggestion!!.contains("MR title"))
            assertTrue(meta.suggestion!!.contains("branch name"))
            assertTrue(meta.suggestion!!.contains("Accented Latin"))
            assertNotNull(meta.explanation)
            assertTrue(meta.explanation!!.contains("team members"))
        }
    }
}

// =========================================================================
// END-TO-END - runs ValidateCommand with the actual NAV config YAML
// written to a temp file, exercising the full karandash config pipeline
// =========================================================================

class MapsConfigE2ETest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = TestFiles.createTestTempDirectory("maps-e2e")
    }

    @AfterTest
    fun cleanup() {
        TestFiles.deleteRecursively(tempDir)
    }

    private var fileCounter = 0

    private fun configFile(): Path {
        val f = Path(tempDir, "config-${fileCounter++}.yaml")
        writeMapsTestFile(f, NAV_CONFIG_YAML.replace("\nai:\n", "\nai:\n  statistics:\n    dir: \"$tempDir/stats\"\n"))
        return f
    }

    private fun mrFile(json: String): Path {
        val f = Path(tempDir, "mr-${fileCounter++}.json")
        writeMapsTestFile(f, json)
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

    // ---- valid MRs ----------------------------------------------------------

    @Test
    fun `valid ios mr passes`() =
        runTest {
            val mr = mrFile(iosMr("[NAV-123][ios] Add login screen"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `valid android mr passes`() =
        runTest {
            val mr = mrFile(androidMr("[NAV-456][android] Fix map rendering"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `valid core mr passes`() =
        runTest {
            val mr = mrFile(coreMr("[NAV-789][core] Update shared models"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `mr with multiple task ids passes`() =
        runTest {
            val mr = mrFile(iosMr("[NAV-123][NAV-456][ios] Multi-task login screen"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `android mr with multiple task ids and changelog body passes`() =
        runTest {
            val mr = mrFile(androidMr("[NAV-123][NAV-456][android] Multi-task map rendering"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    // ---- invalid MRs --------------------------------------------------------

    @Test
    fun `draft mr fails draft prevention`() =
        runTest {
            val mr = mrFile(draftMr("[NAV-123][ios] WIP login screen"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr title without task id passes when commit title also has no task id`() =
        runTest {
            val mr = mrFile(iosMrWithoutTaskId())
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `mr title with wrong context tag fails`() =
        runTest {
            val mr = mrFile(iosMr("[NAV-123][backend] Wrong platform tag"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr title with no context tag fails`() =
        runTest {
            val mr = mrFile(iosMrNoContextTag())
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `ios commit without changelog body passes because commit-body is android-only`() =
        runTest {
            val mr = mrFile(iosMrCommitNoBody("[NAV-123][ios] Add login screen"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `android commit without changelog body passes when title has no NAV task`() =
        runTest {
            val mr = mrFile(androidMrCommitNoBody("[android] Fix map rendering"))
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `android commit without changelog body fails when title contains NAV task`() =
        runTest {
            val mr = mrFile(androidMrCommitNoBody("[NAV-456][android] Fix map rendering"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `android commit without changelog body fails when title contains multiple NAV tasks`() =
        runTest {
            val mr = mrFile(androidMrCommitNoBody("[NAV-123][NAV-456][android] Fix map rendering"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr with cyrillic text in title fails language policy`() =
        runTest {
            val mr = mrFile(iosMr("[NAV-123][ios] \u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u044d\u043a\u0440\u0430\u043d"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `commit with extra task id not in mr title fails cross entity check`() =
        runTest {
            val mr = mrFile(iosMrCrossEntityViolation())
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr title too short fails title format`() =
        runTest {
            val mr = mrFile(iosMr("[ios] Hi"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr title too long fails title format`() =
        runTest {
            val longTitle = "[ios] " + "A".repeat(120)
            val mr = mrFile(iosMr(longTitle))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr title with uppercase IOS context tag fails case sensitive check`() =
        runTest {
            val mr = mrFile(iosMr("[NAV-123][IOS] Uppercase tag"))
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    @Test
    fun `mr with cyrillic text in description passes when checkMrDescription is false`() =
        runTest {
            val mr = mrFile(
                """
                {
                    "id": "20",
                    "iid": 2000,
                    "title": "[NAV-123][ios] Add login screen",
                    "description": "Описание на русском языке",
                    "source_branch": "feature/NAV-123-login",
                    "target_branch": "main",
                    "author": { "username": "john", "name": "John Doe" },
                    "state": "opened",
                    "draft": false,
                    "labels": [],
                    "commits": [
                        {
                            "sha": "abc123",
                            "message": "[NAV-123][ios] Add login screen",
                            "author": { "name": "John", "email": "john@example.com" },
                            "timestamp": "2024-01-15T10:30:00Z"
                        }
                    ],
                    "changes": []
                }
                """.trimIndent(),
            )
            assertEquals(ExitCodes.SUCCESS, validate(configFile(), mr))
        }

    @Test
    fun `mr with cyrillic branch name fails language policy`() =
        runTest {
            val mr = mrFile(
                """
                {
                    "id": "21",
                    "iid": 2100,
                    "title": "[NAV-123][ios] Add login screen",
                    "description": "English description",
                    "source_branch": "feature/добавить-экран",
                    "target_branch": "main",
                    "author": { "username": "john", "name": "John Doe" },
                    "state": "opened",
                    "draft": false,
                    "labels": [],
                    "commits": [
                        {
                            "sha": "abc123",
                            "message": "[NAV-123][ios] Add login screen",
                            "author": { "name": "John", "email": "john@example.com" },
                            "timestamp": "2024-01-15T10:30:00Z"
                        }
                    ],
                    "changes": []
                }
                """.trimIndent(),
            )
            assertEquals(ExitCodes.VALIDATION_FAILED, validate(configFile(), mr))
        }

    // ---- MR JSON builders ---------------------------------------------------

    private fun iosMr(title: String) =
        """
        {
            "id": "1",
            "iid": 100,
            "title": "$title",
            "description": "TASK: https://jira.example.com/browse/NAV-123\n\nCHANGELOG: [NAV-123] Add login screen.\n\nCloses NAV-123\n\nProof: screenshots attached",
            "source_branch": "feature/NAV-123-login-screen",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "[NAV-123][ios] Add login screen\n\nCHANGELOG: [NAV-123] Add login screen.",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun androidMr(title: String) =
        """
        {
            "id": "2",
            "iid": 200,
            "title": "$title",
            "description": "TASK: https://jira.example.com/browse/NAV-456\n\nCHANGELOG: [NAV-456] Fix map rendering.\n\nCloses NAV-456\n\nProof: attached screenshot",
            "source_branch": "feature/NAV-456-map-fix",
            "target_branch": "main",
            "author": { "username": "jane", "name": "Jane Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "def456",
                    "message": "[NAV-456][android] Fix map rendering\n\nCHANGELOG: [NAV-456] Fix map rendering.",
                    "author": { "name": "Jane", "email": "jane@example.com" },
                    "timestamp": "2024-01-15T11:00:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun coreMr(title: String) =
        """
        {
            "id": "3",
            "iid": 300,
            "title": "$title",
            "description": "TASK: https://jira.example.com/browse/NAV-789\n\nCHANGELOG: [NAV-789] Update shared models.\n\nCloses NAV-789\n\nProof: unit tests green",
            "source_branch": "feature/NAV-789-models",
            "target_branch": "main",
            "author": { "username": "dev", "name": "Developer" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "ghi789",
                    "message": "[NAV-789][core] Update shared models\n\nCHANGELOG: [NAV-789] Update shared models.",
                    "author": { "name": "Dev", "email": "dev@example.com" },
                    "timestamp": "2024-01-15T12:00:00Z"
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
            "description": "TASK: https://jira.example.com/browse/NAV-123\n\nCHANGELOG: [NAV-123] WIP.\n\nCloses NAV-123\n\nProof: TBD",
            "source_branch": "feature/NAV-123-wip",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": true,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "[NAV-123][ios] WIP login screen\n\nCHANGELOG: [NAV-123] WIP.",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun iosMrNoContextTag() =
        """
        {
            "id": "5",
            "iid": 500,
            "title": "[NAV-123] Missing context tag",
            "description": "TASK: https://jira.example.com/browse/NAV-123\n\nCHANGELOG: [NAV-123] Some work.\n\nCloses NAV-123\n\nProof: done",
            "source_branch": "feature/NAV-123",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "[NAV-123][ios] Work\n\nCHANGELOG: [NAV-123] Some work.",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun iosMrWithoutTaskId() =
        """
        {
            "id": "9",
            "iid": 900,
            "title": "[ios] Add login screen without ticket",
            "description": "TASK: https://jira.example.com/browse/NAV-123\n\nCHANGELOG: [NAV-123] Add login screen.\n\nCloses NAV-123\n\nProof: screenshots attached",
            "source_branch": "feature/maps-login-screen",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "abc999",
                    "message": "[ios] Add login screen without ticket",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun iosMrCommitNoBody(title: String) =
        """
        {
            "id": "7",
            "iid": 700,
            "title": "$title",
            "description": "TASK: https://jira.example.com/browse/NAV-123\n\nCHANGELOG: [NAV-123] Add login screen.\n\nCloses NAV-123\n\nProof: done",
            "source_branch": "feature/NAV-123-login",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "$title",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun androidMrCommitNoBody(title: String) =
        """
        {
            "id": "10",
            "iid": 1000,
            "title": "$title",
            "description": "TASK: https://jira.example.com/browse/NAV-456\n\nCHANGELOG: [NAV-456] Fix map rendering.\n\nCloses NAV-456\n\nProof: attached screenshot",
            "source_branch": "feature/maps-android-map-fix",
            "target_branch": "main",
            "author": { "username": "jane", "name": "Jane Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "def999",
                    "message": "[android] Fix map rendering",
                    "author": { "name": "Jane", "email": "jane@example.com" },
                    "timestamp": "2024-01-15T11:00:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()

    private fun iosMrCrossEntityViolation() =
        """
        {
            "id": "8",
            "iid": 800,
            "title": "[NAV-123][ios] Single task MR",
            "description": "TASK: https://jira.example.com/browse/NAV-123\n\nCHANGELOG: [NAV-123] Single task.\n\nCloses NAV-123\n\nProof: done",
            "source_branch": "feature/NAV-123",
            "target_branch": "main",
            "author": { "username": "john", "name": "John Doe" },
            "state": "opened",
            "draft": false,
            "labels": [],
            "commits": [
                {
                    "sha": "abc123",
                    "message": "[NAV-123][NAV-999][ios] Work referencing extra task\n\nCHANGELOG: [NAV-123] Single task.",
                    "author": { "name": "John", "email": "john@example.com" },
                    "timestamp": "2024-01-15T10:30:00Z"
                }
            ],
            "changes": []
        }
        """.trimIndent()
}

// =========================================================================
// POLICIES BLOCK - the top-level `policies:` block survives the full config pipeline.
// =========================================================================

class MapsPoliciesBlockTest {
    /**
     * Verifies that the new top-level `policies:` block is parsed correctly.
     * The NAV config must parse without errors.
     */
    @Test
    fun `navigator config with policies block parses successfully`() {
        runTest {
            val rawMap = YamlFormatModule().parseToJsonElement(NAV_CONFIG_YAML)
            val config = ConfigMapParser.parseFullConfig(rawMap).config

            // Config must parse without errors - rules section has 8 rule entries
            assertEquals(8, config.rules.ruleConfigs.size, "rules section must have 8 entries")
        }
    }
}

// =========================================================================
// PROMPT SOURCE REGRESSION - verifies that ai.prompts.review-comments.layered
// resolves to PromptSource.Layered, not Builtin.
//
// Before the fix, PromptSource.fromJson had no `layered:` branch and always
// returned Builtin for this config shape, so the custom review prompt was silently
// ignored every time karandash ran against a navigator project MR.
// =========================================================================

class MapsPromptSourceRegressionTest {
    /**
     * [PromptSource.fromJson] must handle the `layered:` branch.
     *
     * The navigator YAML configures:
     * ```yaml
     * ai:
     *   prompts:
     *     review-comments:
     *       layered:
     *         include-builtin: true
     *         sources:
     *           - file: ".karandash/review-prompt.md"
     * ```
     *
     * [PromptSource.fromJson] must recognise the `layered:` wrapper and return
     * [PromptSource.Layered] - not fall through to [PromptSource.Builtin].
     */
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
