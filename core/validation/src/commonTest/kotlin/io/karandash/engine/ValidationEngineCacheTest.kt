package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.cache.KarandashCache
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.RuleModule
import io.karandash.cache.CacheManager
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
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rule results are cached on MR + config + rule version and re-run when any changes or the cache is off. */
class ValidationEngineCacheTest {
    private class CountingRule(
        override val version: String = "1.0",
        override val supportedEnrichment: List<String> = emptyList(),
    ) : RuleModule {
        var calls = 0
        override val id = "cache-counting"
        override val name = "Cache counting rule"

        override suspend fun contributedRules(): Set<String> = setOf(RULE)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            calls++
            return listOf(Finding(Severity.WARNING, "title: ${mr.title}", ruleId))
        }
    }

    private fun sharedCache(): KarandashCache = CacheManager.disabled().getStringCache("rules", 100)

    private suspend fun engine(
        rule: CountingRule,
        cache: KarandashCache?,
        ruleConfig: JsonElement = JsonObject(emptyMap()),
        enrichment: EnrichmentContext = EnrichmentContext.EMPTY,
    ): ValidationEngine {
        val registry = ModuleRegistryImpl()
        registry.register(rule)
        return ValidationEngine(
            config = Config(rules = RulesConfig(ruleConfigs = mapOf(RULE to ruleConfig))),
            registry = registry,
            configExtractor = { _, ruleId -> if (ruleId == RULE) listOf(ruleConfig) else emptyList() },
            enrichmentContext = enrichment,
            ruleCache = cache,
        )
    }

    private suspend fun ValidationEngine.fromCache(mr: MergeRequest = mr()): Boolean =
        validate(mr).ruleExecutionResults.single { it.ruleId == RULE }.fromCache

    @Test
    fun `second run of an unchanged MR is served from cache with the same findings`() =
        runTest {
            val rule = CountingRule()
            val engine = engine(rule, sharedCache())

            val first = engine.validate(mr())
            val second = engine.validate(mr())

            assertEquals(first.findings.map { it.message }, second.findings.map { it.message })
            assertEquals(true, second.ruleExecutionResults.single().fromCache)
            assertEquals(1, rule.calls)
        }

    @Test
    fun `changed MR re-runs the rule`() =
        runTest {
            val rule = CountingRule()
            val engine = engine(rule, sharedCache())

            engine.validate(mr())
            assertEquals(false, engine.fromCache(mr().copy(title = "Changed title")))
            assertEquals(2, rule.calls)
        }

    @Test
    fun `changed rule config re-runs the rule`() =
        runTest {
            val rule = CountingRule()
            val cache = sharedCache()

            engine(rule, cache).validate(mr())
            val changed = engine(rule, cache, JsonObject(mapOf("max" to JsonPrimitive(1))))
            assertEquals(false, changed.fromCache())
            assertEquals(2, rule.calls)
        }

    @Test
    fun `an upgraded rule module re-runs instead of serving the old version findings`() =
        runTest {
            val cache = sharedCache()
            engine(CountingRule(), cache).validate(mr())

            val upgraded = CountingRule(version = "1.1")
            assertEquals(false, engine(upgraded, cache).fromCache())
            assertEquals(1, upgraded.calls)
        }

    @Test
    fun `null cache - no-cache - always runs the rule`() =
        runTest {
            val rule = CountingRule()
            val engine = engine(rule, null)

            engine.validate(mr())
            assertEquals(false, engine.fromCache())
            assertEquals(2, rule.calls)
        }

    @Test
    fun `changed enrichment data re-runs a rule that receives it and unchanged data hits`() =
        runTest {
            val rule = CountingRule(supportedEnrichment = listOf("jira.*"))
            val cache = sharedCache()

            engine(rule, cache, enrichment = enrichment("jira.issue", "OPEN")).validate(mr())
            assertEquals(true, engine(rule, cache, enrichment = enrichment("jira.issue", "OPEN")).fromCache())
            assertEquals(false, engine(rule, cache, enrichment = enrichment("jira.issue", "DONE")).fromCache())
            assertEquals(2, rule.calls)
        }

    @Test
    fun `a rule that receives no enrichment ignores enrichment changes`() =
        runTest {
            val rule = CountingRule()
            val cache = sharedCache()

            engine(rule, cache, enrichment = enrichment("jira.issue", "OPEN")).validate(mr())
            assertEquals(true, engine(rule, cache, enrichment = enrichment("jira.issue", "DONE")).fromCache())
            assertEquals(1, rule.calls)
        }

    private fun enrichment(
        id: String,
        value: String,
    ) = EnrichmentContext(mapOf(id to JsonPrimitive(value)))

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
        const val RULE = "cache-rule"
    }
}
