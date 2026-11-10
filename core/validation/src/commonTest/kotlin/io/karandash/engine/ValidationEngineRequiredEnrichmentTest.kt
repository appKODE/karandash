package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.finding.SkipCategory
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [ValidationEngine] skips a rule when its [RuleModule.requiredEnrichment] is missing.
 */
class ValidationEngineRequiredEnrichmentTest {
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
        required: List<String> = emptyList(),
        result: List<Finding> = emptyList(),
    ): RuleModule =
        object : RuleModule {
            override val id = "stub-$ruleId"
            override val name = "Stub"
            override val version = "1.0"
            override val dslName = "stub-$ruleId"
            override val requiredEnrichment: List<String> = required

            override suspend fun contributedRules(): Set<String> = setOf(ruleId)

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ) = result
        }

    private fun configFor(ruleId: String): Config = Config(rules = RulesConfig(ruleConfigs = mapOf(ruleId to JsonObject(emptyMap()))))

    @Test
    fun `rule with no requiredEnrichment runs regardless of enrichment context`() =
        runTest {
            val finding = Finding(severity = Severity.ERROR, message = "found it", rule = "testRule")
            val registry = ModuleRegistryImpl()
            registry.register(stubRuleModule("testRule", result = listOf(finding)))

            val engine = ValidationEngine(
                configFor("testRule"),
                registry,
                configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) },
                enrichmentContext = EnrichmentContext.EMPTY,
            )
            val result = engine.validate(makeMR())

            assertEquals(1, result.findings.size)
            assertTrue(result.skippedRules.isEmpty())
        }

    @Test
    fun `rule with requiredEnrichment is skipped when the ID is missing`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(
                stubRuleModule(
                    "testRule",
                    required = listOf("jira:tickets"),
                    result = listOf(Finding(severity = Severity.ERROR, message = "should not run", rule = "testRule")),
                ),
            )

            val engine = ValidationEngine(
                configFor("testRule"),
                registry,
                configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) },
                enrichmentContext = EnrichmentContext.EMPTY,
            )
            val result = engine.validate(makeMR())

            assertTrue(result.findings.isEmpty(), "rule must not run when required enrichment is missing")
            assertEquals(1, result.skippedRules.size)
            assertEquals("testRule", result.skippedRules[0].ruleId)
            assertEquals(SkipCategory.MISSING_REQUIRED_ENRICHMENT, result.skippedRules[0].category)
            assertTrue(result.skippedRules[0].reason.contains("jira:tickets"))
        }

    @Test
    fun `rule with requiredEnrichment runs when the ID is available`() =
        runTest {
            val finding = Finding(severity = Severity.ERROR, message = "found it", rule = "testRule")
            val registry = ModuleRegistryImpl()
            registry.register(
                stubRuleModule("testRule", required = listOf("jira:tickets"), result = listOf(finding)),
            )

            val available = EnrichmentContext.withMissing(
                data = mapOf("jira:tickets" to buildJsonObject { put("key", "PROJ-1") }),
                missingIds = emptySet(),
            )
            val engine = ValidationEngine(
                configFor("testRule"),
                registry,
                configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) },
                enrichmentContext = available,
            )
            val result = engine.validate(makeMR())

            assertEquals(1, result.findings.size)
            assertTrue(result.skippedRules.isEmpty())
        }

    @Test
    fun `rule is skipped when only some required IDs are available`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(
                stubRuleModule(
                    "testRule",
                    required = listOf("jira:tickets", "ci:failed-jobs"),
                    result = listOf(Finding(severity = Severity.ERROR, message = "should not run", rule = "testRule")),
                ),
            )

            val partial = EnrichmentContext.withMissing(
                data = mapOf("jira:tickets" to buildJsonObject { put("key", "PROJ-1") }),
                missingIds = setOf("ci:failed-jobs"),
            )
            val engine = ValidationEngine(
                configFor("testRule"),
                registry,
                configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) },
                enrichmentContext = partial,
            )
            val result = engine.validate(makeMR())

            assertTrue(result.findings.isEmpty())
            assertEquals(1, result.skippedRules.size)
            assertTrue(result.skippedRules[0].reason.contains("ci:failed-jobs"))
        }
}
