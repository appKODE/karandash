package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.config.TimeoutConfig
import io.karandash.duration.KarandashDuration
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `timeouts.ruleExecution`: unset = no per-rule timeout; set = a timed-out rule gives one engine warning.
 * Rules run on Dispatchers.Default, so the delays here are real time.
 */
class ValidationEngineTimeoutTest {
    private class SlowRule(
        private val work: Duration,
        override val executionTimeout: Duration? = null,
    ) : RuleModule {
        override val id = "slow"
        override val name = "Slow rule"
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(RULE)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            delay(work)
            return listOf(Finding(Severity.WARNING, "done", ruleId))
        }
    }

    private suspend fun validate(
        rule: SlowRule,
        ruleExecution: KarandashDuration?,
    ): List<Finding> {
        val registry = ModuleRegistryImpl()
        registry.register(rule)
        val ruleConfig = JsonObject(emptyMap())
        return ValidationEngine(
            config = Config(
                rules = RulesConfig(ruleConfigs = mapOf(RULE to ruleConfig)),
                timeouts = TimeoutConfig(ruleExecution = ruleExecution),
            ),
            registry = registry,
            configExtractor = { _, _ -> listOf(ruleConfig) },
        ).validate(mr()).findings
    }

    @Test
    fun `unset ruleExecution applies no timeout - even to a slow rule with no own budget`() =
        runTest {
            assertEquals(listOf("done"), validate(SlowRule(SLOW), ruleExecution = null).map { it.message })
        }

    @Test
    fun `timed-out rule gives no findings of its own and one engine warning`() =
        runTest {
            val findings = validate(SlowRule(SLOW), ruleExecution = KarandashDuration.ofMillis(SHORT_MS))

            val warning = findings.single()
            assertEquals(ValidationEngine.ENGINE_RULE_ID, warning.rule)
            assertTrue("'$RULE' timed out" in warning.message, warning.message)
        }

    @Test
    fun `a module's own budget wins when it is larger than ruleExecution`() =
        runTest {
            val rule = SlowRule(SLOW, executionTimeout = 10.seconds)
            assertEquals(listOf("done"), validate(rule, ruleExecution = KarandashDuration.ofMillis(SHORT_MS)).map { it.message })
        }

    @Test
    fun `a module's own budget applies alone when ruleExecution is unset`() =
        runTest {
            val findings = validate(SlowRule(10.seconds, executionTimeout = SHORT_MS.milliseconds), ruleExecution = null)
            assertEquals(listOf(ValidationEngine.ENGINE_RULE_ID), findings.map { it.rule })
        }

    private fun mr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Add feature",
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

    private companion object {
        const val RULE = "slow-rule"
        const val SHORT_MS = 50L
        val SLOW = 300.milliseconds
    }
}
