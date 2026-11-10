package io.karandash.enrichment

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.duration.KarandashDuration
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the enrichment engine and context.
 *
 * Verifies the full enrichment flow from registered providers through
 * to rule-consumable context data.
 */
class EnrichmentIntegrationTest {
    private fun makeMR(): MergeRequest =
        MergeRequest(
            id = "42",
            iid = 42,
            title = "feat: integration test",
            description = null,
            author = User(username = "tester", name = "Tester"),
            sourceBranch = "feature/test",
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
        providerId: String,
        ids: List<String>,
        data: Map<String, JsonElement>,
    ): EnrichmentProviderModule =
        object : EnrichmentProviderModule {
            override val id = providerId
            override val name = "Stub $providerId"
            override val version = "1.0"

            override val providedEnrichment: Set<String> get() = ids.toSet()

            override suspend fun enrich(
                mr: MergeRequest,
                config: JsonElement,
                upstreamContext: EnrichmentContext,
            ): Map<String, JsonElement> = data

            override suspend fun initialize(context: PluginContext): Boolean = true
        }

    private fun throwingProvider(providerId: String): EnrichmentProviderModule =
        object : EnrichmentProviderModule {
            override val id = providerId
            override val name = "Throwing $providerId"
            override val version = "1.0"

            override val providedEnrichment: Set<String> get() = setOf("$providerId:data")

            override suspend fun enrich(
                mr: MergeRequest,
                config: JsonElement,
                upstreamContext: EnrichmentContext,
            ): Map<String, JsonElement> = error("Simulated provider failure")

            override suspend fun initialize(context: PluginContext): Boolean = true
        }

    private fun registered(
        module: EnrichmentProviderModule,
        timeout: KarandashDuration = KarandashDuration.ofSeconds(5),
    ): RegisteredEnrichmentProvider =
        RegisteredEnrichmentProvider(
            id = module.id,
            module = module,
            configElement = JsonObject(emptyMap()),
            timeout = timeout,
        )

    @Test
    fun `enrichment engine with mock provider returns data in context`() =
        runTest {
            val data = mapOf(
                "jira:ticket-details" to JsonPrimitive("PROJ-100"),
                "jira:sprint" to JsonPrimitive("Sprint 5"),
            )
            val provider = stubProvider("jira", listOf("jira:ticket-details", "jira:sprint"), data)
            val engine = EnrichmentEngine(listOf(registered(provider)))

            val ctx = engine.enrich(makeMR())

            assertTrue(ctx.has("jira:ticket-details"))
            assertTrue(ctx.has("jira:sprint"))
            assertEquals(JsonPrimitive("PROJ-100"), ctx.get("jira:ticket-details"))
            assertEquals(JsonPrimitive("Sprint 5"), ctx.get("jira:sprint"))
        }

    @Test
    fun `enrichment context filter limits data to bound enrichment IDs`() =
        runTest {
            val jiraData = mapOf(
                "jira:ticket-details" to JsonPrimitive("PROJ-200"),
                "jira:sprint" to JsonPrimitive("Sprint 6"),
            )
            val githubData = mapOf(
                "github:status" to JsonPrimitive("success"),
                "github:checks" to JsonPrimitive("3/3"),
            )
            val engine = EnrichmentEngine(
                listOf(
                    registered(stubProvider("jira", listOf("jira:ticket-details", "jira:sprint"), jiraData)),
                    registered(stubProvider("github", listOf("github:status", "github:checks"), githubData)),
                ),
            )

            val ctx = engine.enrich(makeMR())
            val filtered = ctx.filter(listOf("jira"))

            assertTrue(filtered.has("jira:ticket-details"))
            assertTrue(filtered.has("jira:sprint"))
            assertFalse(filtered.has("github:status"))
            assertFalse(filtered.has("github:checks"))
        }

    @Test
    fun `enrichment context getTyped deserializes list of strings`() =
        runTest {
            val reviewers = listOf("alice", "bob", "carol")
            val data = mapOf(
                "custom:reviewers" to KarandashJson.Default.encodeToJsonElement(
                    ListSerializer(String.serializer()),
                    reviewers,
                ),
            )
            val provider = stubProvider("custom", listOf("custom:reviewers"), data)
            val engine = EnrichmentEngine(listOf(registered(provider)))

            val ctx = engine.enrich(makeMR())

            val result = ctx.getTyped<List<String>>("custom:reviewers")
            assertNotNull(result)
            assertEquals(reviewers, result)
        }

    @Test
    fun `empty providers list produces EMPTY context`() =
        runTest {
            val engine = EnrichmentEngine(emptyList())

            val ctx = engine.enrich(makeMR())

            assertTrue(ctx.availableIds.isEmpty())
            assertEquals(EnrichmentContext.EMPTY.availableIds, ctx.availableIds)
        }

    @Test
    fun `provider that throws still produces context from other providers`() =
        runTest {
            val successData = mapOf("github:status" to JsonPrimitive("passing"))
            val successProvider = stubProvider(
                "github",
                listOf("github:status"),
                successData,
            )
            val engine = EnrichmentEngine(
                listOf(
                    registered(throwingProvider("jira")),
                    registered(successProvider),
                ),
            )

            val ctx = engine.enrich(makeMR())

            assertTrue(ctx.has("github:status"))
            assertFalse(ctx.has("jira:data"))
            assertTrue(ctx.isMissing("jira:data"))
        }

    @Test
    fun `getTyped returns null for missing enrichment ID`() {
        val ctx = EnrichmentContext(
            mapOf("jira:ticket" to JsonPrimitive("PROJ-300")),
        )

        val result = ctx.getTyped<String>("jira:missing")
        assertNull(result)
    }

    @Test
    fun `filter preserves missingIds for matching prefixes`() {
        val ctx = EnrichmentContext.withMissing(
            data = mapOf("jira:ticket" to JsonPrimitive("PROJ-400")),
            missingIds = setOf("jira:sprint", "github:checks"),
        )

        val filtered = ctx.filter(listOf("jira"))

        assertTrue(filtered.isMissing("jira:sprint"))
        assertFalse(filtered.isMissing("github:checks"))
        assertTrue(filtered.has("jira:ticket"))
    }
}
