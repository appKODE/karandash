package io.karandash.validators

import io.karandash.api.ModuleRegistryImpl
import io.karandash.conditions.DefaultPolicyEngine
import io.karandash.config.Config
import io.karandash.config.PolicyConfig
import io.karandash.config.RulesConfig
import io.karandash.config.getRuleConfigElements
import io.karandash.engine.PolicyOptions
import io.karandash.engine.ValidationEngine
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.profiles.IntegrationMrProfileModule
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `profile: integration-mr` through the real engine: its policy lowers a real `commitMessages` error on integration MRs only. */
class IntegrationMrProfileEngineTest {
    private suspend fun commitMessagesSeverities(
        source: String,
        target: String,
    ): List<Severity> {
        val registry = ModuleRegistryImpl()
        registry.register(CommitMessagesRuleModule())
        val policies = IntegrationMrProfileModule().policies().map {
            KarandashJson.Default.decodeFromJsonElement(PolicyConfig.serializer(), it)
        }
        val config = Config(
            rules = RulesConfig(
                ruleConfigs = mapOf(CommitMessagesRuleModule.RULE_ID to CommitMessagesConfig(format = "conventional").toJsonElement()),
            ),
        )
        val engine = ValidationEngine(
            config = config,
            registry = registry,
            configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            policy = PolicyOptions(engine = DefaultPolicyEngine(), policies = policies),
        )
        val commit = Commit("abc123", "not conventional", CommitAuthor("dev", "dev@example.com"), "2026-01-01T00:00:00Z")
        val mr = createTestMergeRequest(sourceBranch = source, targetBranch = target, commits = listOf(commit))
        return engine
            .validate(mr)
            .findings
            .filter { it.rule == CommitMessagesRuleModule.RULE_ID }
            .map { it.severity }
    }

    @Test
    fun `feature into develop ends WARNING while a hotfix branch stays ERROR`() =
        runTest {
            assertEquals(listOf(Severity.WARNING), commitMessagesSeverities("feature/x", "develop"))
            assertEquals(listOf(Severity.ERROR), commitMessagesSeverities("hotfix/x", "develop"))
        }
}
