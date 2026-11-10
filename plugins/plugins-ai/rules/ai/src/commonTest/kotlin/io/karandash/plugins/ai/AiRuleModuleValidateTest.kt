package io.karandash.plugins.ai

import io.karandash.api.PluginContext
import io.karandash.api.context.ValidationContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [AiRuleModule.validate] covering the dispatch and error-handling
 * paths that are not exercised by the individual rule-analyzer tests.
 */
class AiRuleModuleValidateTest {
    private fun createContext(
        pluginConfig: Map<String, Any?>,
        envVars: Map<String, String> = emptyMap()
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(pluginConfig)

            override fun env(name: String): String? = envVars[name]

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private fun makeMr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test PR",
            description = "Some description",
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User("u1", "dev", "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = listOf(
                Commit("sha1", "Test commit", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z")
            ),
            changes = listOf(
                FileChange("src/Main.kt", ChangeType.MODIFIED, additions = 1, deletions = 0)
            )
        )

    @Test
    fun `validate before initialize returns error finding`() =
        runTest {
            val module = AiRuleModule(buildTestRegistry())

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertEquals(Severity.ERROR, findings[0].severity)
            assertTrue(findings[0].message.contains("not initialized"))
            assertEquals("risk-scoring", findings[0].rule)
        }

    @Test
    fun `validate with unknown ruleId returns error finding`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model"
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val findings = module.validate("unknown-rule-id", makeMr(), JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertEquals(Severity.ERROR, findings[0].severity)
            assertTrue(findings[0].message.contains("is not registered"))
            assertEquals("unknown-rule-id", findings[0].rule)

            module.shutdown()
        }

    @Test
    fun `validate with exhausted budget returns empty list`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "maxTokenBudget" to 0
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertTrue(findings.isEmpty())

            module.shutdown()
        }

    @Test
    fun `initialize with extraInstructions config succeeds`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "extraInstructions" to "Focus on security issues",
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertTrue(result)
            module.shutdown()
        }

    @Test
    fun `initialize with promptOverrides config succeeds`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "promptOverrides" to mapOf(
                        "risk-scoring" to "Custom risk prompt",
                    ),
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertTrue(result)
            module.shutdown()
        }

    @Test
    fun `initialize with gemini provider succeeds`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf("provider" to "gemini"),
                envVars = mapOf("GEMINI_API_KEY" to "test-gemini-key"),
            )
            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertTrue(result)
            module.shutdown()
        }

    @Test
    fun `initialize with gemini provider without key fails`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf("provider" to "gemini"),
            )
            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertTrue(!result)
        }

    @Test
    fun `initialize with modelFallback config succeeds`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "modelFallback" to listOf("gpt-4o", "gpt-4o-mini"),
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertTrue(result)
            module.shutdown()
        }

    @Test
    fun `validate with dryRun returns info finding`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "dryRun" to true,
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertEquals(Severity.INFO, findings[0].severity)
            assertTrue(findings[0].message.contains("[DRY RUN]"))
            assertTrue(findings[0].message.contains("custom"))
            // Stub analyzers make no AI call; the report still says what would have been sent.
            assertTrue(
                Regex("Would send \\d+ request\\(s\\)").containsMatchIn(findings[0].message),
                findings[0].message
            )
            assertEquals("dry run", module.skippedRuleReasons()["risk-scoring"]?.reason)

            module.shutdown()
        }

    @Test
    fun `initialize with dryRun env var sets dryRunAi`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                ),
                envVars = mapOf("KARANDASH_AI_DRY_RUN" to "true"),
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertTrue(findings[0].message.contains("[DRY RUN]"))

            module.shutdown()
        }

    @Test
    fun `initialize with concurrency config succeeds`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "concurrency" to 3,
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertTrue(result)
            module.shutdown()
        }

    @Test
    fun `validate with no hooks configured works normally`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "dryRun" to true,
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertEquals(Severity.INFO, findings[0].severity)
            assertTrue(findings[0].message.contains("[DRY RUN]"))

            module.shutdown()
        }

    @Test
    fun `validate with insufficient per-rule budget returns empty list and warns`() =
        runTest {
            val warnings = mutableListOf<String>()
            val context = object : PluginContext {
                override val pluginConfig: JsonElement = mapToJsonElement(
                    mapOf(
                        "provider" to "custom",
                        "baseUrl" to "http://localhost:1234/v1",
                        "model" to "test-model",
                        // Budget is large enough to pass initialize() check but too small for any single rule
                        "maxTokenBudget" to 1,
                    )
                )

                override fun env(name: String): String? = null

                override fun log(message: String) {}

                override fun warn(message: String) {
                    warnings.add(message)
                }

                override val hostVersion: String = "1.0.0-test"
                override val workingDirectory: String = "/tmp/test"
            }
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertTrue(findings.isEmpty())
            assertTrue(warnings.any { it.contains("token budget exhausted") && it.contains("risk-scoring") }, warnings.toString())

            module.shutdown()
        }

    @Test
    fun `contributedRules returns all enabled rules by default`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val rules = module.contributedRules()

            assertEquals(2, rules.size)
            assertTrue(rules.contains("risk-scoring"))
            assertTrue("description-quality" !in rules)
            assertTrue("change-categorization" !in rules)
            assertTrue(rules.contains("review-comments"))
            assertTrue("similar-issues" !in rules)
            assertTrue("fix-suggestions" !in rules)

            module.shutdown()
        }

    @Test
    fun `allowlist mode — 4 explicit enables run only those 4 rules`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "features" to mapOf(
                        "riskScoring" to true,
                        "reviewComments" to true,
                        "reviewThreadAnalysis" to true,
                        "mrSummary" to true,
                    ),
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val rules = module.contributedRules()

            assertEquals(4, rules.size)
            assertTrue(rules.contains("risk-scoring"))
            assertTrue(rules.contains("review-comments"))
            assertTrue(rules.contains("review-thread-analysis"))
            assertTrue(rules.contains("mr-summary"))

            module.shutdown()
        }

    @Test
    fun `deny-list mode — 1 explicit disable runs 1 default rule`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "features" to mapOf("riskScoring" to false),
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val rules = module.contributedRules()

            assertEquals(1, rules.size)
            assertTrue("risk-scoring" !in rules)
            assertTrue("description-quality" !in rules)
            assertTrue("change-categorization" !in rules)
            assertTrue(rules.contains("review-comments"))
            assertTrue("similar-issues" !in rules)
            assertTrue("fix-suggestions" !in rules)

            module.shutdown()
        }

    @Test
    fun `mixed mode — enable and disable preserves default behavior`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "features" to mapOf(
                        "riskScoring" to true,
                        "reviewComments" to false,
                    ),
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val rules = module.contributedRules()

            // Mixed: hasExplicitEnables=true, hasExplicitDisables=true → defaultForBuiltins=true
            // So all 2 defaults run, minus reviewComments = 1 rule
            assertEquals(1, rules.size)
            assertTrue(rules.contains("risk-scoring"))
            assertTrue("description-quality" !in rules)
            assertTrue("change-categorization" !in rules)
            assertTrue("review-comments" !in rules)
            assertTrue("similar-issues" !in rules)
            assertTrue("fix-suggestions" !in rules)

            module.shutdown()
        }

    @Test
    fun `empty features map runs all 2 default rules`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "features" to emptyMap<String, Any?>(),
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val rules = module.contributedRules()

            assertEquals(2, rules.size)

            module.shutdown()
        }

    @Test
    fun `FALLBACK strategy skip sets ruleSkipReasons and appears in cost summary`() =
        runTest {
            val logs = mutableListOf<String>()
            val pluginContext = object : PluginContext {
                override val pluginConfig: JsonElement = mapToJsonElement(
                    mapOf(
                        "provider" to "custom",
                        "baseUrl" to "http://localhost:1234/v1",
                        "model" to "test-model",
                        "rules" to mapOf(
                            "risk-scoring" to mapOf(
                                "strategy" to "fallback",
                                "covers" to listOf("title-format"),
                            )
                        ),
                    )
                )

                override fun env(name: String): String? = null

                override fun log(message: String) {
                    logs.add(message)
                }

                override fun warn(message: String) {}

                override val hostVersion: String = "1.0.0-test"
                override val workingDirectory: String = "/tmp/test"
            }

            val module = AiRuleModule(buildTestRegistry())
            module.initialize(pluginContext)

            val validationContext = ValidationContext(
                priorFindings = listOf(
                    Finding(severity = Severity.WARNING, message = "Bad title", rule = "title-format")
                )
            )

            val findings = module.validateWithFindings(
                "risk-scoring",
                makeMr(),
                JsonObject(emptyMap()),
                validationContext,
            )

            assertTrue(findings.isEmpty())
            assertTrue(
                logs.any { it.contains("Skipping") && it.contains("fallback") && it.contains("title-format") },
                "Expected a log message about skipping due to fallback, got: $logs"
            )

            module.logCostSummary()

            assertTrue(
                logs.any {
                    it.contains("risk-scoring") &&
                        it.contains("skipped") &&
                        it.contains("fallback — covered by title-format")
                },
                "Expected cost summary to show 'skipped (fallback — covered by title-format)', got: $logs"
            )

            module.shutdown()
        }

    @Test
    fun `dryRun with per-rule model using short name resolves correctly`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "global-model",
                    "dryRun" to true,
                    "models" to mapOf(
                        "risk-scoring" to "short-name-model",
                    ),
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertTrue(findings[0].message.contains("short-name-model"))

            module.shutdown()
        }

    @Test
    fun `dryRun with per-rule model using full ai- prefix resolves correctly`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "global-model",
                    "dryRun" to true,
                    "models" to mapOf(
                        "risk-scoring" to "full-prefix-model",
                    ),
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertTrue(findings[0].message.contains("full-prefix-model"))

            module.shutdown()
        }

    @Test
    fun `dryRun without per-rule model falls back to global model`() =
        runTest {
            val context = createContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "global-model",
                    "dryRun" to true,
                    "models" to mapOf(
                        "review-thread-analysis" to "other-rule-model",
                    ),
                )
            )
            val module = AiRuleModule(buildTestRegistry())
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertTrue(findings[0].message.contains("global-model"))

            module.shutdown()
        }
}
