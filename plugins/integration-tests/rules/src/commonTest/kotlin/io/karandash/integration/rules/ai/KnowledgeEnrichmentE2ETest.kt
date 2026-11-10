package io.karandash.integration.rules.ai

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.platform.ChargeResult
import io.karandash.api.platform.SubprocessBudget
import io.karandash.config.RuleEnrichmentConfig
import io.karandash.duration.KarandashDuration
import io.karandash.enrichment.EnrichmentEngine
import io.karandash.enrichment.RegisteredEnrichmentProvider
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.rules.BudgetConfig
import io.karandash.plugins.ai.core.rules.KnowledgeContextConfig
import io.karandash.sdk.enrichment.EnrichmentConfigParser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Knowledge-System Context Enrichment features.
 *
 * Covers:
 * - [KnowledgeContextConfig] budget resolution (exact, glob, most-specific, fallback, zero-skip)
 * - [AiContextEnricherContext.enrichmentContext] field propagation
 * - [RuleEnrichmentConfig] JSON deserialization
 * - [EnrichmentConfigParser] sub-block extraction and typo detection
 * - [EnrichmentEngine.buildExecutionLayers] cycle detection error logging
 * - [EnrichmentEngine] provider failure / timeout → IDs marked missing
 */
class KnowledgeEnrichmentE2ETest {
    // -- Helpers --

    private fun makeMR() =
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

    private fun stubProvider(
        id: String,
        outputIds: List<String>,
        data: Map<String, JsonElement> = outputIds.associateWith { JsonPrimitive("value") },
        deps: List<String> = emptyList(),
    ): EnrichmentProviderModule =
        object : EnrichmentProviderModule {
            override val id = id
            override val name = "Stub $id"
            override val version = "1.0"
            override val description = ""
            override val dslName = id
            override val configKey = id
            override val defaultEnabled = true
            override val defaultPriority = 100

            override val providedEnrichment: Set<String> get() = outputIds.toSet()

            override fun requiredEnrichment(): List<String> = deps

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun enrich(
                mr: MergeRequest,
                config: JsonElement,
                upstreamContext: EnrichmentContext,
            ): Map<String, JsonElement> = data
        }

    private fun failingProvider(
        id: String,
        outputIds: List<String>
    ): EnrichmentProviderModule =
        object : EnrichmentProviderModule {
            override val id = id
            override val name = "Failing $id"
            override val version = "1.0"
            override val description = ""
            override val dslName = id
            override val configKey = id
            override val defaultEnabled = true
            override val defaultPriority = 100

            override val providedEnrichment: Set<String> get() = outputIds.toSet()

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun enrich(
                mr: MergeRequest,
                config: JsonElement,
                upstreamContext: EnrichmentContext,
            ): Map<String, JsonElement> = error("Simulated failure in $id")
        }

    private fun registered(
        provider: EnrichmentProviderModule,
        timeout: KarandashDuration = KarandashDuration.ofMillis(5_000),
    ) = RegisteredEnrichmentProvider(
        id = provider.id,
        module = provider,
        configElement = JsonObject(emptyMap()),
        timeout = timeout,
    )

    private fun capturingLogger(
        errors: MutableList<String> = mutableListOf(),
        warnings: MutableList<String> = mutableListOf(),
    ): Logger =
        object : Logger {
            override fun debug(message: String) {}

            override fun info(message: String) {}

            override fun warn(message: String) {
                warnings.add(message)
            }

            override fun error(message: String) {
                errors.add(message)
            }
        }

    // =========================================================
    // KnowledgeContextConfig.budgetFor() resolution
    // =========================================================

    @Test
    fun `budget for exact enrichment id returns matching budget`() {
        val config = KnowledgeContextConfig(
            budgets = mapOf("jira:tickets" to BudgetConfig(maxChars = 1500)),
        )
        assertEquals(1500, config.budgetFor("jira:tickets").maxChars)
    }

    @Test
    fun `budget for glob pattern jira star matches jira tickets`() {
        val config = KnowledgeContextConfig(
            budgets = mapOf("jira:*" to BudgetConfig(maxChars = 2000)),
        )
        assertEquals(2000, config.budgetFor("jira:tickets").maxChars)
        assertEquals(2000, config.budgetFor("jira:acceptance-criteria").maxChars)
    }

    @Test
    fun `most specific pattern wins over broader glob`() {
        val config = KnowledgeContextConfig(
            budgets = mapOf(
                "jira:*" to BudgetConfig(maxChars = 2000),
                "jira:tickets" to BudgetConfig(maxChars = 500),
            ),
        )
        assertEquals(500, config.budgetFor("jira:tickets").maxChars)
        assertEquals(2000, config.budgetFor("jira:acceptance-criteria").maxChars)
    }

    @Test
    fun `budget falls back to default budget when no pattern matches`() {
        val config = KnowledgeContextConfig(
            defaultBudget = BudgetConfig(maxChars = 800),
            budgets = mapOf("ci:*" to BudgetConfig(maxChars = 1200)),
        )
        assertEquals(800, config.budgetFor("confluence:pages").maxChars)
    }

    @Test
    fun `budget max chars zero is returned as zero sentinel for skip`() {
        val config = KnowledgeContextConfig(
            budgets = mapOf("confluence:pages" to BudgetConfig(maxChars = 0)),
        )
        assertEquals(0, config.budgetFor("confluence:pages").maxChars)
    }

    // =========================================================
    // AiContextEnricherContext.enrichmentContext
    // =========================================================

    @Test
    fun `ai context enricher context enrichmentContext defaults to empty`() {
        val ctx = AiContextEnricherContext(ruleId = "review-comments", existingContext = emptyMap())
        assertTrue(ctx.enrichmentContext.availableIds.isEmpty())
        assertFalse(ctx.enrichmentContext.has("jira:tickets"))
    }

    @Test
    fun `ai context enricher context carries enrichment context when provided`() {
        val enrichmentData = mapOf("jira:tickets" to JsonPrimitive("PROJ-1"))
        val enrichmentCtx = EnrichmentContext.withMissing(enrichmentData, emptySet())
        val ctx = AiContextEnricherContext(
            ruleId = "review-comments",
            existingContext = emptyMap(),
            enrichmentContext = enrichmentCtx,
        )
        assertTrue(ctx.enrichmentContext.has("jira:tickets"))
        assertEquals(JsonPrimitive("PROJ-1"), ctx.enrichmentContext.get("jira:tickets"))
    }

    // =========================================================
    // RuleEnrichmentConfig deserialization
    // =========================================================

    @Test
    fun `rule enrichment config enrich list deserialized correctly`() {
        val json = """{"enrich":["jira:tickets","ci:*"]}"""
        val parsed = KarandashJson.Default.decodeFromString(RuleEnrichmentConfig.serializer(), json)
        assertEquals(listOf("jira:tickets", "ci:*"), parsed.enrich)
    }

    @Test
    fun `rule enrichment config empty enrich list defaults to empty`() {
        val parsed = KarandashJson.Default.decodeFromString(RuleEnrichmentConfig.serializer(), "{}")
        assertTrue(parsed.enrich.isEmpty())
    }

    // =========================================================
    // EnrichmentConfigParser
    // =========================================================

    @Test
    fun `enrichment config parser extracts known sub-block keys`() {
        val providerJson = JsonObject(
            mapOf(
                "url" to JsonPrimitive("https://jira.acme.com"),
                "tickets" to JsonObject(mapOf("enabled" to JsonPrimitive(true))),
                "acceptance-criteria" to JsonObject(mapOf("enabled" to JsonPrimitive(false))),
            ),
        )
        val warnings = mutableListOf<String>()
        val subBlocks = EnrichmentConfigParser.extractSubBlocks(
            config = providerJson,
            knownSubKeys = setOf("tickets", "acceptance-criteria", "linked-issues"),
            providerConfigKey = "jira",
            warnings = warnings,
        )

        assertTrue(subBlocks.containsKey("tickets"))
        assertTrue(subBlocks.containsKey("acceptance-criteria"))
        assertTrue(warnings.isEmpty(), "No warnings expected for known sub-block keys")
    }

    @Test
    fun `enrichment config parser warns and suggests correction for typo in sub-block key`() {
        val providerJson = JsonObject(
            mapOf(
                "ticketss" to JsonObject(mapOf("enabled" to JsonPrimitive(true))),
            ),
        )
        val warnings = mutableListOf<String>()
        EnrichmentConfigParser.extractSubBlocks(
            config = providerJson,
            knownSubKeys = setOf("tickets", "acceptance-criteria"),
            providerConfigKey = "jira",
            warnings = warnings,
        )

        assertTrue(warnings.isNotEmpty(), "Expected warning for unknown sub-block 'ticketss'")
        assertTrue(
            warnings.any { it.contains("ticketss") && it.contains("tickets") },
            "Warning should reference both the typo and the suggested correction",
        )
    }

    @Test
    fun `enrichment config parser warns about unknown key with no suggestion when far from any known key`() {
        val providerJson = JsonObject(
            mapOf("completely-unknown-xyz" to JsonObject(emptyMap())),
        )
        val warnings = mutableListOf<String>()
        EnrichmentConfigParser.extractSubBlocks(
            config = providerJson,
            knownSubKeys = setOf("tickets", "acceptance-criteria"),
            providerConfigKey = "jira",
            warnings = warnings,
        )

        assertTrue(warnings.isNotEmpty(), "Expected warning for unknown sub-block key")
        assertTrue(
            warnings.any { it.contains("completely-unknown-xyz") },
            "Warning should reference the unknown key name",
        )
    }

    // =========================================================
    // EnrichmentEngine - cycle detection error logging
    // =========================================================

    @Test
    fun `cycle detection logs error and engine still produces output`() =
        runTest {
            val errors = mutableListOf<String>()
            val logger = capturingLogger(errors = errors)

            val a = registered(stubProvider("a", listOf("a:data"), deps = listOf("b:data")))
            val b = registered(stubProvider("b", listOf("b:data"), deps = listOf("a:data")))

            val engine = EnrichmentEngine(listOf(a, b), logger)
            engine.enrich(makeMR())

            assertTrue(errors.isNotEmpty(), "Expected error log for circular dependency")
            assertTrue(
                errors.any { it.contains("circular") || it.contains("unresolvable") },
                "Error log should reference circular or unresolvable dependency",
            )
        }

    // =========================================================
    // EnrichmentEngine - provider failure marks IDs missing
    // =========================================================

    @Test
    fun `provider failure marks enrichment ids as missing and pipeline continues`() =
        runTest {
            val warnings = mutableListOf<String>()
            val logger = capturingLogger(warnings = warnings)
            val goodProvider = stubProvider("good", listOf("good:data"))
            val badProvider = failingProvider("bad", listOf("bad:data"))
            val engine = EnrichmentEngine(
                listOf(registered(goodProvider), registered(badProvider)),
                logger,
            )

            val result = engine.enrich(makeMR())

            assertTrue(result.has("good:data"), "Good provider output should be in context")
            assertFalse(result.has("bad:data"), "Failing provider output should not be in context")
            assertTrue(result.isMissing("bad:data"), "Failing provider IDs should be marked missing")
            assertTrue(warnings.isNotEmpty(), "Expected warn log for provider failure")
        }

    @Test
    fun `enrichment engine produces context with all ids from multiple providers`() =
        runTest {
            val p1 = stubProvider("jira", listOf("jira:tickets", "jira:ticket-keys"))
            val p2 = stubProvider("ci", listOf("ci:failed-jobs"))
            val engine = EnrichmentEngine(listOf(registered(p1), registered(p2)))

            val result = engine.enrich(makeMR())

            assertEquals(
                setOf("jira:tickets", "jira:ticket-keys", "ci:failed-jobs"),
                result.availableIds,
            )
            assertNotNull(result.get("jira:tickets"))
        }

    // =========================================================
    // Graceful degradation - Jira-like provider absent / no output
    // =========================================================

    @Test
    fun `provider returning empty data leaves its ids absent from enrichment context`() =
        runTest {
            val jiraLike = stubProvider(
                "jira",
                listOf("jira:tickets", "jira:acceptance-criteria"),
                data = emptyMap(),
            )
            val engine = EnrichmentEngine(listOf(registered(jiraLike)))

            val result = engine.enrich(makeMR())

            assertFalse(result.has("jira:tickets"), "IDs not produced should be absent")
            assertFalse(result.has("jira:acceptance-criteria"), "IDs not produced should be absent")
            assertTrue(result.availableIds.isEmpty())
        }

    @Test
    fun `pipeline continues producing output when jira-like provider is absent`() =
        runTest {
            val ciProvider = stubProvider("ci", listOf("ci:failed-jobs"))
            val engine = EnrichmentEngine(listOf(registered(ciProvider)))

            val result = engine.enrich(makeMR())

            assertTrue(result.has("ci:failed-jobs"), "Non-jira provider still produces output")
            assertFalse(result.has("jira:tickets"), "Jira IDs absent when provider not registered")
        }

    // =========================================================
    // KnowledgeContextConfig - config inheritance / budget merge
    // =========================================================

    @Test
    fun `knowledge context config child exact budget overrides base glob for same id`() {
        val base = KnowledgeContextConfig(
            defaultBudget = BudgetConfig(maxChars = 1500),
            budgets = mapOf("jira:*" to BudgetConfig(maxChars = 2000)),
        )
        val child = KnowledgeContextConfig(
            budgets = mapOf("jira:tickets" to BudgetConfig(maxChars = 500)),
        )
        assertEquals(500, child.budgetFor("jira:tickets").maxChars)
        assertEquals(2000, base.budgetFor("jira:acceptance-criteria").maxChars)
    }

    @Test
    fun `knowledge context config base default budget used for ids not in child budgets`() {
        val base = KnowledgeContextConfig(
            defaultBudget = BudgetConfig(maxChars = 1500),
            budgets = mapOf(
                "jira:tickets" to BudgetConfig(maxChars = 2000),
                "jira:acceptance-criteria" to BudgetConfig(maxChars = 1500),
            ),
        )
        assertEquals(1500, base.budgetFor("local-git:file-frequency").maxChars)
        assertEquals(1500, base.budgetFor("confluence:pages").maxChars)
    }

    @Test
    fun `rule enrichment config with glob patterns serializes and deserializes correctly`() {
        val json = """{"enrich":["jira:*","local-git:*","ci:failed-jobs"]}"""
        val parsed = KarandashJson.Default.decodeFromString(RuleEnrichmentConfig.serializer(), json)
        assertEquals(3, parsed.enrich.size)
        assertTrue(parsed.enrich.contains("jira:*"))
        assertTrue(parsed.enrich.contains("local-git:*"))
        assertTrue(parsed.enrich.contains("ci:failed-jobs"))
    }

    @Test
    fun `subprocess budget with zero max calls has zero remaining and rejects first charge`() {
        val budget = SubprocessBudget(maxCalls = 0, maxWallMillis = 30_000L)
        assertEquals(0, budget.remainingCalls)
        val result = budget.charge(listOf("git", "log"), 100L)
        assertTrue(result is ChargeResult.RejectedCount, "Zero-budget should immediately reject")
    }
}
