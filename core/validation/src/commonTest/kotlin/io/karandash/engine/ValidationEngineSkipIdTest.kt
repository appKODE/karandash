package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.finding.SkipCategory
import io.karandash.api.finding.SkippedRuleInfo
import io.karandash.api.module.RuleModule
import io.karandash.api.module.SkipReasonProvider
import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Skips from every source end up under one rule id style: the id the module contributed. */
class ValidationEngineSkipIdTest {
    private class SkippingRule :
        RuleModule,
        SkipReasonProvider {
        override val id = "skipping"
        override val name = "Skipping rule"
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(RULE)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> = emptyList()

        // keyed by the config key, as an engine skip is; the module's own skip names the kebab id
        override fun skippedRuleReasons(): Map<String, SkippedRuleInfo> =
            mapOf(
                CONFIG_KEY to SkippedRuleInfo(CONFIG_KEY, "provider error: unreachable: host", SkipCategory.AI_PROVIDER_ERROR),
            )
    }

    @Test
    fun `a module skip keyed by the config key is reported under the rule id`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(SkippingRule())
            val ruleConfig = JsonObject(emptyMap())
            val result = ValidationEngine(
                config = Config(rules = RulesConfig(ruleConfigs = mapOf(RULE to ruleConfig))),
                registry = registry,
                configExtractor = { _, _ -> listOf(ruleConfig) },
            ).validate(mr())

            assertEquals(listOf(RULE), result.skippedRules.map { it.ruleId })
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
        const val RULE = "risk-scoring"
        const val CONFIG_KEY = "riskScoring"
    }
}
