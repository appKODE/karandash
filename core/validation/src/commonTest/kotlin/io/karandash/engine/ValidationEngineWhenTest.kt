package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.finding.SkipCategory
import io.karandash.api.module.RuleModule
import io.karandash.config.Config
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.config.WhenConfig
import io.karandash.config.getRuleConfigElements
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidationEngineWhenTest {
    private fun makeMr(draft: Boolean = false): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "feat: add feature",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/my-branch",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = draft,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    /** Stub rule that records the config it receives and optionally produces findings. */
    private class RecordingRuleModule(
        override val id: String,
        private val findings: List<Finding> = emptyList(),
    ) : RuleModule {
        override val name: String = "Recording Rule $id"
        override val version: String = "1.0"
        override val dslName: String = id

        var lastReceivedConfig: JsonElement? = null
            private set

        override suspend fun contributedRules(): Set<String> = setOf(id)

        override suspend fun initialize(ctx: PluginContext): Boolean = true

        override suspend fun shutdown() {}

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            lastReceivedConfig = configElement
            return findings
        }
    }

    @Test
    fun `rule without when runs unconditionally`() =
        runTest {
            val module = RecordingRuleModule(
                "stub-rule",
                findings = listOf(Finding(rule = "stub-rule", message = "found", severity = Severity.WARNING)),
            )
            val registry = ModuleRegistryImpl()
            registry.register(module)
            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("stubRule" to JsonObject(emptyMap()))),
            )
            val result = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            ).validate(makeMr())
            assertEquals(1, result.findings.size)
            assertTrue(result.skippedRules.isEmpty())
        }

    @Test
    fun `rule with when evaluated to false is skipped`() =
        runTest {
            val module = RecordingRuleModule(
                "draft-rule",
                findings = listOf(Finding(rule = "draft-rule", message = "found", severity = Severity.ERROR)),
            )
            val registry = ModuleRegistryImpl()
            registry.register(module)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("draftRule" to JsonObject(emptyMap())),
                    ruleMeta = mapOf(
                        "draftRule" to RuleExecutionMeta(`when` = WhenConfig(expression = "is_draft")),
                    ),
                ),
            )
            // MR is NOT draft → condition false → rule skipped
            val result = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            ).validate(makeMr(draft = false))
            assertTrue(result.findings.isEmpty())
            assertTrue(result.skippedRules.any { it.ruleId == "draftRule" })
            assertNull(module.lastReceivedConfig)
        }

    @Test
    fun `rule with when evaluated to true is executed`() =
        runTest {
            val module = RecordingRuleModule(
                "draft-rule",
                findings = listOf(Finding(rule = "draft-rule", message = "found", severity = Severity.WARNING)),
            )
            val registry = ModuleRegistryImpl()
            registry.register(module)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("draftRule" to JsonObject(emptyMap())),
                    ruleMeta = mapOf(
                        "draftRule" to RuleExecutionMeta(`when` = WhenConfig(expression = "is_draft")),
                    ),
                ),
            )
            // MR IS draft → condition true → rule runs
            val result = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            ).validate(makeMr(draft = true))
            assertEquals(1, result.findings.size)
            assertTrue(result.skippedRules.isEmpty())
        }

    @Test
    fun `skipped rule appears in skippedRules list`() =
        runTest {
            val module = RecordingRuleModule("cond-rule")
            val registry = ModuleRegistryImpl()
            registry.register(module)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("condRule" to JsonObject(emptyMap())),
                    ruleMeta = mapOf(
                        "condRule" to RuleExecutionMeta(`when` = WhenConfig(expression = "is_draft")),
                    ),
                ),
            )
            val result = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            ).validate(makeMr(draft = false))
            assertEquals(1, result.skippedRules.size)
            assertEquals("condRule", result.skippedRules[0].ruleId)
        }

    @Test
    fun `when override is deep-merged into rule config before execution`() =
        runTest {
            val baseConfig = buildJsonObject { put("required", JsonPrimitive(false)) }
            val overrideObj = buildJsonObject { put("required", JsonPrimitive(true)) }

            val module = RecordingRuleModule("body-rule")
            val registry = ModuleRegistryImpl()
            registry.register(module)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("bodyRule" to baseConfig),
                    ruleMeta = mapOf(
                        "bodyRule" to RuleExecutionMeta(
                            `when` = WhenConfig(expression = "is_draft", override = overrideObj),
                        ),
                    ),
                ),
            )
            // MR is draft → condition true → override applied
            ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            ).validate(makeMr(draft = true))

            val received = assertNotNull(module.lastReceivedConfig)
            assertEquals(
                "true",
                received.jsonObject["required"]?.jsonPrimitive?.content,
                "override should set required=true",
            )
        }

    @Test
    fun `when override is not applied when condition is false`() =
        runTest {
            val baseConfig = buildJsonObject { put("required", JsonPrimitive(false)) }
            val overrideObj = buildJsonObject { put("required", JsonPrimitive(true)) }

            val module = RecordingRuleModule("body-rule")
            val registry = ModuleRegistryImpl()
            registry.register(module)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("bodyRule" to baseConfig),
                    ruleMeta = mapOf(
                        "bodyRule" to RuleExecutionMeta(
                            `when` = WhenConfig(expression = "is_draft", override = overrideObj),
                        ),
                    ),
                ),
            )
            // MR is NOT draft → condition false → rule skipped, override never applied
            val result = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            ).validate(makeMr(draft = false))
            assertTrue(result.skippedRules.any { it.ruleId == "bodyRule" })
            assertNull(module.lastReceivedConfig, "rule should not have been called")
        }

    @Test
    fun `when override does not mutate original rule config`() =
        runTest {
            val baseConfig = buildJsonObject { put("required", JsonPrimitive(false)) }
            val overrideObj = buildJsonObject { put("required", JsonPrimitive(true)) }

            val module = RecordingRuleModule("body-rule")
            val registry = ModuleRegistryImpl()
            registry.register(module)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("bodyRule" to baseConfig),
                    ruleMeta = mapOf(
                        "bodyRule" to RuleExecutionMeta(
                            `when` = WhenConfig(expression = "is_draft", override = overrideObj),
                        ),
                    ),
                ),
            )
            // Run with draft=true → override applied in runtime copy
            ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            ).validate(makeMr(draft = true))

            // Original config in the Config object must remain unchanged
            val originalRequired = config.rules.ruleConfigs["bodyRule"]
                ?.jsonObject
                ?.get("required")
                ?.jsonPrimitive
                ?.content
            assertEquals("false", originalRequired, "original base config must not be mutated")
        }

    @Test
    fun `when override kebab-case keys normalized to camelCase before merge`() =
        runTest {
            // when.override arrives with kebab-case keys (e.g. match-mode) from YAML.
            // filterByWhen must normalize them to camelCase before deep-merging into the
            // base rule config so rule modules receive the expected key names.
            val baseConfig = buildJsonObject {
                put("maxLength", JsonPrimitive(100))
            }
            val overrideObj = buildJsonObject {
                put("match-mode", JsonPrimitive("prefix"))
                put("max-length", JsonPrimitive(50))
            }

            val module = RecordingRuleModule("body-rule")
            val registry = ModuleRegistryImpl()
            registry.register(module)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("bodyRule" to baseConfig),
                    ruleMeta = mapOf(
                        "bodyRule" to RuleExecutionMeta(
                            `when` = WhenConfig(expression = "is_draft", override = overrideObj),
                        ),
                    ),
                ),
            )
            ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            ).validate(makeMr(draft = true))

            val received = assertNotNull(module.lastReceivedConfig).jsonObject
            assertNull(received["match-mode"], "kebab-case key must not appear in merged config")
            assertNull(received["max-length"], "kebab-case key must not appear in merged config")
            assertEquals(
                "prefix",
                received["matchMode"]?.jsonPrimitive?.content,
                "camelCase matchMode key must be present after normalization",
            )
            assertEquals(
                "50",
                received["maxLength"]?.jsonPrimitive?.content,
                "camelCase maxLength key must be present after normalization",
            )
        }

    @Test
    fun `malformed when expression skips the rule with an engine warning and the parse error as skip reason`() =
        runTest {
            val module = RecordingRuleModule(
                "bad-when-rule",
                findings = listOf(Finding(rule = "bad-when-rule", message = "found", severity = Severity.ERROR)),
            )
            val registry = ModuleRegistryImpl()
            registry.register(module)
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("badWhenRule" to JsonObject(emptyMap())),
                    ruleMeta = mapOf(
                        "badWhenRule" to RuleExecutionMeta(
                            `when` = WhenConfig(expression = "!!!invalid_expression!!!"),
                        ),
                    ),
                ),
            )
            val result = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            ).validate(makeMr())
            val warning = result.findings.single()
            assertEquals(ValidationEngine.ENGINE_RULE_ID, warning.rule)
            assertEquals(Severity.WARNING, warning.severity)
            assertTrue("badWhenRule" in warning.message, warning.message)
            val skip = result.skippedRules.single { it.ruleId == "badWhenRule" }
            assertEquals(SkipCategory.WHEN_ERROR, skip.category)
            assertTrue(skip.reason.startsWith("when expression failed: "), skip.reason)
            assertNull(module.lastReceivedConfig)
        }
}
