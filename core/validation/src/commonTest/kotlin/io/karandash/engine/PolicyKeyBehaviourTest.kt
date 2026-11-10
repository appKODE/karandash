package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.conditions.DefaultPolicyEngine
import io.karandash.config.Config
import io.karandash.config.ExpressionsLimitsConfig
import io.karandash.config.PolicyConfig
import io.karandash.config.RulesConfig
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** One test per `policies:` entry key, each setting a non-default value and asserting what it changes. */
class PolicyKeyBehaviourTest {
    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "feat: x",
        description = null,
        author = User(username = "alice", name = "alice"),
        sourceBranch = "feature/x",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
        approvals = emptyList(),
        reviewers = emptyList(),
    )

    private fun max(value: Int) = JsonObject(mapOf("max" to JsonPrimitive(value)))

    private val config = Config(
        rules = RulesConfig(ruleConfigs = mapOf("titleLength" to max(10), "labels" to JsonObject(emptyMap()))),
    )

    private suspend fun apply(
        policy: PolicyConfig,
        limits: ExpressionsLimitsConfig? = null,
    ) = PolicyApplicator(
        config = config,
        registry = ModuleRegistryImpl(),
        policyEngine = limits?.let { DefaultPolicyEngine(NoOpLogger, null, it.effectiveMaxLength, it.effectiveMaxNestingDepth) }
            ?: DefaultPolicyEngine(),
        policies = listOf(policy),
        logger = NoOpLogger,
    ).applyPolicies(mr)

    @Test
    fun `limits expressions maxLength 100 rejects a long condition and 10000 accepts it`() =
        runTest {
            val long = (1..6).joinToString(" || ") { "branch_matches(\"^feature/\")" }
            val policy = PolicyConfig(condition = long, enabled = false)

            val tight = apply(policy, ExpressionsLimitsConfig(maxLength = 100))
            val roomy = apply(policy, ExpressionsLimitsConfig(maxLength = 10_000))

            assertEquals(listOf(false, true), listOf(tight, roomy).map { it.activePolicies.single().matched })
        }

    @Test
    fun `limits expressions maxNestingDepth 5 rejects deep parentheses and 50 accepts them`() =
        runTest {
            val deep = "(".repeat(8) + "branch_matches(\"^feature/\")" + ")".repeat(8)
            val policy = PolicyConfig(condition = deep, enabled = false)

            val tight = apply(policy, ExpressionsLimitsConfig(maxNestingDepth = 5))
            val roomy = apply(policy, ExpressionsLimitsConfig(maxNestingDepth = 50))

            assertEquals(listOf(false, true), listOf(tight, roomy).map { it.activePolicies.single().matched })
        }

    @Test
    fun `policies when decides whether the entry applies`() =
        runTest {
            val matched = apply(PolicyConfig(condition = MATCHES, enabled = false))
            val unmatched = apply(PolicyConfig(condition = NO_MATCH, enabled = false))

            assertEquals(mapOf("titleLength" to false, "labels" to false), matched.enabledOverrides)
            assertEquals(emptyMap(), unmatched.enabledOverrides)
            assertEquals(listOf(true, false), listOf(matched, unmatched).map { it.activePolicies.single().matched })
        }

    @Test
    fun `policies name labels the entry in the skipped override report`() =
        runTest {
            val skipped = apply(
                PolicyConfig(condition = NO_MATCH, name = "hotfix-window", override = JsonObject(mapOf("titleLength" to max(99)))),
            ).skippedPolicyOverrides

            assertEquals(listOf("hotfix-window"), skipped.map { it.policy })
        }

    @Test
    fun `policies modules scopes enabled false to the listed rule only`() =
        runTest {
            val result = apply(PolicyConfig(condition = MATCHES, modules = listOf("labels"), enabled = false))

            assertEquals(mapOf("labels" to false), result.enabledOverrides)
        }

    @Test
    fun `policies enabled false switches every configured rule off`() =
        runTest {
            val result = apply(PolicyConfig(condition = MATCHES, enabled = false))

            assertEquals(false, result.enabledOverrides["titleLength"])
            assertEquals(false, result.enabledOverrides["labels"])
        }

    @Test
    fun `policies severity error forces that severity on the scoped rule`() =
        runTest {
            val meta = apply(PolicyConfig(condition = MATCHES, modules = listOf("labels"), severity = "error"))
                .runtimeConfig.rules.ruleMeta

            assertEquals("error", meta["labels"]?.severity)
            assertNull(meta["titleLength"])
        }

    @Test
    fun `policies override max 99 replaces the rule value only when the entry matches`() =
        runTest {
            val override = JsonObject(mapOf("titleLength" to max(99)))
            val matched = apply(PolicyConfig(condition = MATCHES, override = override))
            val unmatched = apply(PolicyConfig(condition = NO_MATCH, override = override))

            assertEquals(max(99), matched.runtimeConfig.rules.ruleConfigs["titleLength"])
            assertSame(config, unmatched.runtimeConfig)
        }

    @Test
    fun `policies autoApprove false switches the stage off and an unmatched entry leaves it unset`() =
        runTest {
            assertEquals(false, apply(PolicyConfig(condition = MATCHES, autoApprove = false)).autoApproveOverride)
            assertNull(apply(PolicyConfig(condition = NO_MATCH, autoApprove = false)).autoApproveOverride)
        }

    @Test
    fun `policies autoFix false switches the stage off and an unmatched entry leaves it unset`() =
        runTest {
            assertEquals(false, apply(PolicyConfig(condition = MATCHES, autoFix = false)).autoFixOverride)
            assertNull(apply(PolicyConfig(condition = NO_MATCH, autoFix = false)).autoFixOverride)
        }

    @Test
    fun `policies scripts false switches the stage off and an unmatched entry leaves it unset`() =
        runTest {
            assertEquals(false, apply(PolicyConfig(condition = MATCHES, scripts = false)).scriptsOverride)
            assertNull(apply(PolicyConfig(condition = NO_MATCH, scripts = false)).scriptsOverride)
        }

    @Test
    fun `policies dangerfile false switches the stage off and an unmatched entry leaves it unset`() =
        runTest {
            assertEquals(false, apply(PolicyConfig(condition = MATCHES, dangerfile = false)).dangerfileOverride)
            assertNull(apply(PolicyConfig(condition = NO_MATCH, dangerfile = false)).dangerfileOverride)
        }
}

private const val MATCHES = "branch_matches(\"^feature/\")"

private const val NO_MATCH = "branch_matches(\"^hotfix/\")"
