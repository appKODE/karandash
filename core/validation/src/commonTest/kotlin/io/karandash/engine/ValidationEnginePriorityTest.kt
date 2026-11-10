package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.config.Config
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationEnginePriorityTest {
    private fun makeMR(): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = emptyList(),
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun finding(
        rule: String,
        severity: Severity = Severity.ERROR
    ) = Finding(severity = severity, message = "test", rule = rule)

    private fun stubRuleModule(
        ruleId: String,
        result: List<Finding>
    ): RuleModule =
        object : RuleModule {
            override val id = "stub-$ruleId"
            override val name = "Stub"
            override val version = "1.0"
            override val dslName = "stub-$ruleId"

            override suspend fun contributedRules(): Set<String> = setOf(ruleId)

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ) = result
        }

    @Test
    fun `rules without priority run in default group`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("ruleA", listOf(finding("ruleA", Severity.WARNING))))

            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("ruleA" to JsonObject(emptyMap())))
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, ruleId ->
                if (ruleId == "ruleA") listOf(JsonObject(emptyMap())) else emptyList()
            })

            val result = engine.validate(makeMR())
            assertEquals(1, result.findings.size)
            assertTrue(result.passed)
        }

    @Test
    fun `bail-on-fail stops lower priority groups on error`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("blocker", listOf(finding("blocker", Severity.ERROR))))
            registry.register(stubRuleModule("skippable", emptyList()))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "blocker" to JsonObject(emptyMap()),
                        "skippable" to JsonObject(emptyMap())
                    ),
                    ruleMeta = mapOf(
                        "blocker" to RuleExecutionMeta(priority = 1, bailOnFail = true),
                        "skippable" to RuleExecutionMeta(priority = 10)
                    )
                )
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })

            val result = engine.validate(makeMR())
            assertEquals(1, result.findings.size)
            assertEquals(1, result.skippedRules.size)
            assertEquals("skippable", result.skippedRules[0].ruleId)
        }

    @Test
    fun `bail-on-fail does not trigger on warning`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("warnRule", listOf(finding("warnRule", Severity.WARNING))))
            registry.register(stubRuleModule("nextRule", listOf(finding("nextRule", Severity.WARNING))))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "warnRule" to JsonObject(emptyMap()),
                        "nextRule" to JsonObject(emptyMap())
                    ),
                    ruleMeta = mapOf(
                        "warnRule" to RuleExecutionMeta(priority = 1, bailOnFail = true),
                        "nextRule" to RuleExecutionMeta(priority = 10)
                    )
                )
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })

            val result = engine.validate(makeMR())
            assertEquals(2, result.findings.size)
            assertTrue(result.skippedRules.isEmpty())
        }

    @Test
    fun `backwards compat - config without priority runs all rules`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("ruleX", listOf(finding("ruleX", Severity.WARNING))))
            registry.register(stubRuleModule("ruleY", listOf(finding("ruleY", Severity.WARNING))))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("ruleX" to JsonObject(emptyMap()), "ruleY" to JsonObject(emptyMap()))
                    // no ruleMeta → all default priority=100
                )
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })

            val result = engine.validate(makeMR())
            assertEquals(2, result.findings.size)
            assertTrue(result.skippedRules.isEmpty())
            assertTrue(result.passed)
        }

    @Test
    fun `skippedRules is empty when no bail triggered`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val config = Config(rules = RulesConfig())
            val engine = ValidationEngine(config, registry)

            val result = engine.validate(makeMR())
            assertTrue(result.skippedRules.isEmpty())
        }

    @Test
    fun `passed is false when error findings present`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("errRule", listOf(finding("errRule", Severity.ERROR))))

            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("errRule" to JsonObject(emptyMap())))
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })

            val result = engine.validate(makeMR())
            assertTrue(result.findings.any { it.severity == Severity.ERROR })
            assertFalse(result.passed)
        }
}
