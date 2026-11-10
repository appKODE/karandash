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
import kotlin.test.assertNull

class ValidationEngineEnrichmentTest {
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
    fun `user config suggestion overrides validator default`() =
        runTest {
            val finding = Finding(
                severity = Severity.ERROR,
                message = "bad title",
                rule = "testRule",
                suggestion = "validator default suggestion"
            )
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("testRule", listOf(finding)))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("testRule" to JsonObject(emptyMap())),
                    ruleMeta = mapOf(
                        "testRule" to RuleExecutionMeta(suggestion = "user override suggestion")
                    )
                )
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })
            val result = engine.validate(makeMR())

            assertEquals(1, result.findings.size)
            assertEquals("user override suggestion", result.findings[0].suggestion)
        }

    @Test
    fun `user config explanation overrides validator default`() =
        runTest {
            val finding = Finding(
                severity = Severity.ERROR,
                message = "bad title",
                rule = "testRule",
                explanation = "validator default explanation"
            )
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("testRule", listOf(finding)))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("testRule" to JsonObject(emptyMap())),
                    ruleMeta = mapOf(
                        "testRule" to RuleExecutionMeta(explanation = "user override explanation")
                    )
                )
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })
            val result = engine.validate(makeMR())

            assertEquals(1, result.findings.size)
            assertEquals("user override explanation", result.findings[0].explanation)
        }

    @Test
    fun `validator defaults preserved when no user config`() =
        runTest {
            val finding = Finding(
                severity = Severity.ERROR,
                message = "bad title",
                rule = "testRule",
                suggestion = "validator suggestion",
                explanation = "validator explanation"
            )
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("testRule", listOf(finding)))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("testRule" to JsonObject(emptyMap()))
                )
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })
            val result = engine.validate(makeMR())

            assertEquals(1, result.findings.size)
            assertEquals("validator suggestion", result.findings[0].suggestion)
            assertEquals("validator explanation", result.findings[0].explanation)
        }

    @Test
    fun `null user config does not override non-null validator default`() =
        runTest {
            val finding = Finding(
                severity = Severity.ERROR,
                message = "bad title",
                rule = "testRule",
                suggestion = "validator suggestion",
                explanation = "validator explanation"
            )
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("testRule", listOf(finding)))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("testRule" to JsonObject(emptyMap())),
                    ruleMeta = mapOf(
                        "testRule" to RuleExecutionMeta(priority = 50)
                    )
                )
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })
            val result = engine.validate(makeMR())

            assertEquals(1, result.findings.size)
            assertEquals("validator suggestion", result.findings[0].suggestion)
            assertEquals("validator explanation", result.findings[0].explanation)
        }

    @Test
    fun `partial override only replaces specified field`() =
        runTest {
            val finding = Finding(
                severity = Severity.ERROR,
                message = "bad title",
                rule = "testRule",
                suggestion = "validator suggestion",
                explanation = "validator explanation"
            )
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("testRule", listOf(finding)))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("testRule" to JsonObject(emptyMap())),
                    ruleMeta = mapOf(
                        "testRule" to RuleExecutionMeta(suggestion = "user suggestion only")
                    )
                )
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })
            val result = engine.validate(makeMR())

            assertEquals(1, result.findings.size)
            assertEquals("user suggestion only", result.findings[0].suggestion)
            assertEquals("validator explanation", result.findings[0].explanation)
        }

    @Test
    fun `findings without suggestion or explanation are unchanged when no meta`() =
        runTest {
            val finding = Finding(
                severity = Severity.WARNING,
                message = "test warning",
                rule = "testRule"
            )
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("testRule", listOf(finding)))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("testRule" to JsonObject(emptyMap()))
                )
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })
            val result = engine.validate(makeMR())

            assertEquals(1, result.findings.size)
            assertNull(result.findings[0].suggestion)
            assertNull(result.findings[0].explanation)
        }
}
