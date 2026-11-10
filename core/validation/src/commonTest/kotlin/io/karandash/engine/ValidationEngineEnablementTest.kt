package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.config.Config
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
import kotlin.test.assertTrue

class ValidationEngineEnablementTest {
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

    private fun stubRuleModule(
        ruleId: String,
        findings: List<Finding>,
        defaultEnabled: Boolean = true,
    ): RuleModule =
        object : RuleModule {
            override val id = "stub-$ruleId"
            override val name = "Stub"
            override val version = "1.0"
            override val dslName = "stub-$ruleId"
            override val defaultEnabled = defaultEnabled

            override suspend fun contributedRules(): Set<String> = setOf(ruleId)

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ) = findings
        }

    private fun configExtractor(vararg ruleIds: String): (Config, String) -> List<JsonElement> =
        { _, ruleId ->
            if (ruleId in ruleIds) listOf(JsonObject(emptyMap())) else emptyList()
        }

    @Test
    fun `explicitly disabled rule is skipped`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("ruleA", listOf(Finding(Severity.ERROR, "fail", "ruleA"))))

            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("ruleA" to JsonObject(emptyMap())))
            )
            val engine = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = configExtractor("ruleA"),
                enabledModules = mapOf("ruleA" to false),
            )

            val result = engine.validate(makeMR())
            assertTrue(result.findings.isEmpty(), "Disabled rule should produce no findings")
        }

    @Test
    fun `explicitly enabled rule executes normally`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("ruleA", listOf(Finding(Severity.ERROR, "fail", "ruleA"))))

            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("ruleA" to JsonObject(emptyMap())))
            )
            val engine = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = configExtractor("ruleA"),
                enabledModules = mapOf("ruleA" to true),
            )

            val result = engine.validate(makeMR())
            assertEquals(1, result.findings.size)
        }

    @Test
    fun `rule with no enablement entry falls back to defaultEnabled`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(
                stubRuleModule("ruleA", listOf(Finding(Severity.ERROR, "fail", "ruleA")), defaultEnabled = false)
            )

            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("ruleA" to JsonObject(emptyMap())))
            )
            val engine = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = configExtractor("ruleA"),
                enabledModules = emptyMap(),
            )

            val result = engine.validate(makeMR())
            assertTrue(result.findings.isEmpty(), "Rule with defaultEnabled=false should be skipped")
        }

    @Test
    fun `mixed enabled and disabled rules`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("ruleA", listOf(Finding(Severity.ERROR, "A", "ruleA"))))
            registry.register(stubRuleModule("ruleB", listOf(Finding(Severity.WARNING, "B", "ruleB"))))
            registry.register(stubRuleModule("ruleC", listOf(Finding(Severity.INFO, "C", "ruleC"))))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "ruleA" to JsonObject(emptyMap()),
                        "ruleB" to JsonObject(emptyMap()),
                        "ruleC" to JsonObject(emptyMap()),
                    )
                )
            )
            val engine = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = configExtractor("ruleA", "ruleB", "ruleC"),
                enabledModules = mapOf("ruleA" to true, "ruleB" to false, "ruleC" to true),
            )

            val result = engine.validate(makeMR())
            assertEquals(2, result.findings.size)
            val rules = result.findings.map { it.rule }.toSet()
            assertTrue("ruleA" in rules)
            assertTrue("ruleC" in rules)
        }
}
