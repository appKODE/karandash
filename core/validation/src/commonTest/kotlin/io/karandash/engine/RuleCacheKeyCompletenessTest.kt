package io.karandash.engine

import io.karandash.api.PluginContext
import io.karandash.api.cache.KarandashCache
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.context.ValidationContext
import io.karandash.api.module.RuleModule
import io.karandash.cache.CacheManager
import io.karandash.config.Config
import io.karandash.logger.NoOpLogger
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

/** Every input a rule's findings depend on is part of its cache key, and only those inputs are. */
class RuleCacheKeyCompletenessTest {
    private class Rule(
        override val id: String = "key-rule",
        override val version: String = "1.0",
        override val supportedEnrichment: List<String> = listOf("jira.*"),
    ) : RuleModule {
        override val name = "Key rule"

        override suspend fun contributedRules(): Set<String> = setOf(id)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> = emptyList()
    }

    /**
     * One run's inputs to [RuleResultCache.cachedValidate].
     *
     * Not @Serializable: carries a live [RuleModule].
     */
    private data class Inputs(
        val module: RuleModule = Rule(),
        val mr: MergeRequest = mr(),
        val runtimeConfig: Config = Config(),
        val configs: List<JsonElement> = listOf(JsonObject(emptyMap())),
        val context: ValidationContext? = null,
        val enrichment: EnrichmentContext = enrichment("OPEN"),
    )

    private val cache: KarandashCache = CacheManager.disabled().getStringCache("rules", 100)

    /** Runs [inputs] through a fresh per-run cache over the shared store; true when served from cache. */
    private suspend fun hit(
        inputs: Inputs,
        invalidated: Set<String> = emptySet(),
    ): Boolean {
        val results = RuleResultCache(cache, NoOpLogger, inputs.enrichment, invalidatedRules = invalidated)
        val ruleId = inputs.module.id
        return results
            .cachedValidate(inputs.module, ruleId, inputs.mr, inputs.runtimeConfig, inputs.configs, inputs.context) {
                emptyList()
            }.second
    }

    @Test
    fun `each input a rule depends on misses the cache when it changes`() =
        runTest {
            val perturbations =
                mapOf(
                    "mr" to Inputs(mr = mr().copy(title = "Other")),
                    "config" to Inputs(configs = listOf(JsonObject(mapOf("max" to JsonPrimitive(1))))),
                    "runtimeConfig" to Inputs(runtimeConfig = Config(baseline = "other.json")),
                    "implementation" to Inputs(module = Rule(version = "2.0")),
                    "context" to Inputs(context = ValidationContext(listOf(Finding(Severity.ERROR, "x", "other")))),
                    "enrichment" to Inputs(enrichment = enrichment("DONE")),
                )
            perturbations.forEach { (input, changed) ->
                hit(Inputs())
                assertEquals(true, hit(Inputs()), "baseline should hit before changing $input")
                assertEquals(false, hit(changed), "a changed $input must miss")
            }
        }

    @Test
    fun `an enrichment change a rule does not receive keeps its cache entry`() =
        runTest {
            val plain = Inputs(module = Rule(supportedEnrichment = emptyList()))
            hit(plain)
            assertEquals(true, hit(plain.copy(enrichment = enrichment("DONE"))))
        }

    @Test
    fun `an invalidated rule recomputes and writes back while other rules stay cached`() =
        runTest {
            val a = Inputs(module = Rule(id = "rule-a"))
            val b = Inputs(module = Rule(id = "rule-b"))
            hit(a)
            hit(b)

            assertEquals(false, hit(a, invalidated = setOf("rule-a")))
            assertEquals(true, hit(b, invalidated = setOf("rule-a")))
            assertEquals(true, hit(a))
        }

    private companion object {
        fun enrichment(status: String) = EnrichmentContext(mapOf("jira.issue" to JsonPrimitive(status)))

        fun mr() =
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
    }
}
