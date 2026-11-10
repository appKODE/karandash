package io.karandash.contracts.fw

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.engine.ValidationEngine
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
 * The rule-result cache is never used.
 * `ValidationEngine.executeRule` always runs the rule and records `fromCache = false`
 * and `RuleCacheHelper.cachedValidate` has no production callers. Validating an unchanged MR twice therefore re-runs every rule.
 */
class RuleCacheContractTest {
    @Test
    fun `second validate of an unchanged MR serves the rule from cache`() =
        runTest {
            val engine = engine(mutableListOf())

            engine.validate(mr())
            val second = engine.validate(mr())

            val execution = second.ruleExecutionResults.last { it.ruleId == RULE_ID }
            assertTrue(execution.fromCache, "second run of an unchanged MR reported fromCache=false")
        }

    @Test
    fun `rule is invoked once across two validations of an unchanged MR`() =
        runTest {
            val calls = mutableListOf<String>()
            val engine = engine(calls)

            engine.validate(mr())
            engine.validate(mr())

            assertEquals(listOf(RULE_ID), calls, "rule re-executed although MR and config are unchanged")
        }

    private suspend fun engine(calls: MutableList<String>): ValidationEngine {
        val registry = ModuleRegistryImpl()
        registry.register(countingRule(calls))
        return ValidationEngine(
            config = Config(rules = RulesConfig(ruleConfigs = mapOf(RULE_ID to JsonObject(emptyMap())))),
            registry = registry,
            configExtractor = { _, ruleId -> if (ruleId == RULE_ID) listOf(JsonObject(emptyMap())) else emptyList() },
        )
    }

    private fun countingRule(calls: MutableList<String>): RuleModule =
        object : RuleModule {
            override val id = "counting"
            override val name = "counting rule"
            override val version = "1.0"
            override val dslName = "counting"

            override suspend fun contributedRules(): Set<String> = setOf(RULE_ID)

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ): List<Finding> {
                calls += ruleId
                return listOf(Finding(Severity.WARNING, "title checked", ruleId))
            }
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
        const val RULE_ID = "rule"
    }
}
