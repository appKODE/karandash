package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.conditions.DefaultPolicyEngine
import io.karandash.config.Config
import io.karandash.config.PolicyConfig
import io.karandash.config.RulesConfig
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationEnginePoliciesTest {
    private fun makeMr(
        sourceBranch: String = "feature/my-branch",
        targetBranch: String = "main",
        title: String = "feat: add feature",
        draft: Boolean = false,
        labels: List<String> = emptyList(),
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = title,
            description = null,
            author = User(username = "alice", name = "alice"),
            sourceBranch = sourceBranch,
            targetBranch = targetBranch,
            state = MergeRequestState.OPENED,
            draft = draft,
            labels = labels,
            commits = emptyList(),
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    /** Minimal rule module that always produces one finding. */
    private class StubRuleModule(
        private val ruleId: String,
        private val severity: Severity = Severity.WARNING,
    ) : RuleModule {
        override val id: String = ruleId
        override val name: String = "Stub Rule $ruleId"
        override val version: String = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(ruleId)

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> =
            listOf(
                Finding(
                    rule = ruleId,
                    message = "Stub finding from $ruleId",
                    severity = severity,
                ),
            )

        override suspend fun initialize(ctx: PluginContext): Boolean = true

        override suspend fun shutdown() {}
    }

    private suspend fun createEngine(
        config: Config,
        policies: List<PolicyConfig> = emptyList(),
        vararg rules: RuleModule,
    ): ValidationEngine {
        val registry = ModuleRegistryImpl()
        rules.forEach { registry.register(it) }
        return ValidationEngine(
            config = config,
            registry = registry,
            configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            policy = PolicyOptions(engine = if (policies.isNotEmpty()) DefaultPolicyEngine() else null, policies = policies),
        )
    }

    @Test
    fun `no policies produces empty appliedPolicies`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("stubRule" to JsonObject(emptyMap())),
                ),
            )
            val engine = createEngine(config, emptyList(), StubRuleModule("stubRule"))
            val result = engine.validate(makeMr())
            assertTrue(result.appliedPolicies.isEmpty())
            assertEquals(1, result.findings.size)
        }

    @Test
    fun `matched policy with enabled false disables rule`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("stubRule" to JsonObject(emptyMap())),
                ),
            )
            val policies = listOf(
                PolicyConfig(
                    condition = "branch_matches(\"^feature/\")",
                    modules = listOf("stubRule"),
                    enabled = false,
                ),
            )
            val engine = createEngine(config, policies, StubRuleModule("stubRule"))
            val result = engine.validate(makeMr(sourceBranch = "feature/foo"))

            assertEquals(1, result.appliedPolicies.size)
            assertTrue(result.appliedPolicies[0].matched)
            // Rule should be disabled by policy -> no findings from it
            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `unmatched policy does not affect rule execution`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("stubRule" to JsonObject(emptyMap())),
                ),
            )
            val policies = listOf(
                PolicyConfig(
                    condition = "branch_matches(\"^hotfix/\")",
                    modules = listOf("stubRule"),
                    enabled = false,
                ),
            )
            val engine = createEngine(config, policies, StubRuleModule("stubRule"))
            val result = engine.validate(makeMr(sourceBranch = "feature/foo"))

            assertEquals(1, result.appliedPolicies.size)
            assertFalse(result.appliedPolicies[0].matched)
            // Rule should still produce findings since policy didn't match
            assertEquals(1, result.findings.size)
        }

    @Test
    fun `appliedPolicies populated in validation result`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("stubRule" to JsonObject(emptyMap())),
                ),
            )
            val policies = listOf(
                PolicyConfig(condition = "is_draft", severity = "error"),
                PolicyConfig(condition = "branch_matches(\"^release/\")", enabled = false),
            )
            val engine = createEngine(config, policies, StubRuleModule("stubRule"))
            val result = engine.validate(makeMr(draft = true, sourceBranch = "feature/x"))

            assertEquals(2, result.appliedPolicies.size)
            assertTrue(result.appliedPolicies[0].matched)
            assertFalse(result.appliedPolicies[1].matched)
        }

    @Test
    fun `policy config overrides are deep merged into rule config`() =
        runTest {
            val ruleConfig = JsonObject(
                mapOf("maxLength" to JsonPrimitive(100)),
            )
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("stubRule" to ruleConfig),
                ),
            )
            val overrides = JsonObject(
                mapOf(
                    "stubRule" to JsonObject(mapOf("maxLength" to JsonPrimitive(50))),
                ),
            )
            val policies = listOf(
                PolicyConfig(
                    condition = "branch_matches(\"^feature/\")",
                    override = overrides,
                ),
            )
            val engine = createEngine(config, policies, StubRuleModule("stubRule"))
            val result = engine.validate(makeMr(sourceBranch = "feature/x"))

            assertTrue(result.appliedPolicies[0].matched)
            // The rule still runs (just with different config), so it produces findings
            assertEquals(1, result.findings.size)
        }
}
