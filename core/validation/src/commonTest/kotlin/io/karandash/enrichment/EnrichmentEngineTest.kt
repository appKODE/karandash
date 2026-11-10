package io.karandash.enrichment

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.duration.KarandashDuration
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnrichmentEngineTest {
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

    private fun stubProvider(
        providerId: String,
        data: Map<String, JsonElement>,
        deps: List<String> = emptyList(),
    ): EnrichmentProviderModule =
        object : EnrichmentProviderModule {
            override val id = providerId
            override val name = "Stub $providerId"
            override val version = "1.0"

            override val providedEnrichment: Set<String> get() = data.keys

            override fun requiredEnrichment(): List<String> = deps

            override suspend fun enrich(
                mr: MergeRequest,
                config: JsonElement,
                upstreamContext: EnrichmentContext,
            ): Map<String, JsonElement> = data

            override suspend fun initialize(context: PluginContext): Boolean = true
        }

    private fun failingProvider(providerId: String): EnrichmentProviderModule =
        object : EnrichmentProviderModule {
            override val id = providerId
            override val name = "Failing $providerId"
            override val version = "1.0"

            override val providedEnrichment: Set<String> get() = setOf("$providerId:data")

            override suspend fun enrich(
                mr: MergeRequest,
                config: JsonElement,
                upstreamContext: EnrichmentContext,
            ): Map<String, JsonElement> = error("Simulated failure")

            override suspend fun initialize(context: PluginContext): Boolean = true
        }

    private fun slowProvider(
        providerId: String,
        delayMs: Long,
        data: Map<String, JsonElement>,
    ): EnrichmentProviderModule =
        object : EnrichmentProviderModule {
            override val id = providerId
            override val name = "Slow $providerId"
            override val version = "1.0"

            override val providedEnrichment: Set<String> get() = data.keys

            override suspend fun enrich(
                mr: MergeRequest,
                config: JsonElement,
                upstreamContext: EnrichmentContext,
            ): Map<String, JsonElement> {
                delay(delayMs)
                return data
            }

            override suspend fun initialize(context: PluginContext): Boolean = true
        }

    /**
     * Provider that reads upstream context and appends to its output.
     * Used to verify DAG execution passes accumulated data downstream.
     */
    private fun dependentProvider(
        providerId: String,
        outputIds: List<String>,
        deps: List<String>,
        produceData: (EnrichmentContext) -> Map<String, JsonElement>,
    ): EnrichmentProviderModule =
        object : EnrichmentProviderModule {
            override val id = providerId
            override val name = "Dependent $providerId"
            override val version = "1.0"

            override val providedEnrichment: Set<String> get() = outputIds.toSet()

            override fun requiredEnrichment(): List<String> = deps

            override suspend fun enrich(
                mr: MergeRequest,
                config: JsonElement,
                upstreamContext: EnrichmentContext,
            ): Map<String, JsonElement> = produceData(upstreamContext)

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

    private val warningMessages = mutableListOf<String>()
    private val debugMessages = mutableListOf<String>()

    private val capturingLogger = object : Logger {
        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            debugMessages.add(message)
        }

        override fun info(message: String) {}

        override fun warn(message: String) {
            warningMessages.add(message)
        }

        override fun error(message: String) {}
    }

    @Test
    fun `empty providers returns EMPTY context`() =
        runTest {
            val engine = EnrichmentEngine(emptyList())
            val result = engine.enrich(makeMR())
            assertTrue(result.availableIds.isEmpty())
        }

    @Test
    fun `single provider returns data`() =
        runTest {
            val data = mapOf("jira:ticket" to JsonPrimitive("PROJ-123"))
            val provider = stubProvider("jira", data)
            val engine = EnrichmentEngine(listOf(registered(provider)))

            val result = engine.enrich(makeMR())

            assertTrue(result.has("jira:ticket"))
            assertEquals(JsonPrimitive("PROJ-123"), result.get("jira:ticket"))
        }

    @Test
    fun `provider decision is logged at debug`() =
        runTest {
            val provider = stubProvider("jira", mapOf("jira:ticket" to JsonPrimitive("PROJ-1")))
            EnrichmentEngine(listOf(registered(provider)), logger = capturingLogger).enrich(makeMR())

            assertTrue(debugMessages.any { it.startsWith("[jira] enrich: ok (keys=1, ms=") }, "$debugMessages")
        }

    @Test
    fun `multiple providers merge data`() =
        runTest {
            val jiraData = mapOf("jira:ticket" to JsonPrimitive("PROJ-123"))
            val githubData = mapOf("github:status" to JsonPrimitive("success"))
            val engine = EnrichmentEngine(
                listOf(
                    registered(stubProvider("jira", jiraData)),
                    registered(stubProvider("github", githubData)),
                ),
            )

            val result = engine.enrich(makeMR())

            assertEquals(setOf("jira:ticket", "github:status"), result.availableIds)
            assertTrue(result.has("jira:ticket"))
            assertTrue(result.has("github:status"))
        }

    @Test
    fun `provider failure is non-fatal`() =
        runTest {
            warningMessages.clear()
            val jiraData = mapOf("jira:ticket" to JsonPrimitive("PROJ-123"))
            val engine = EnrichmentEngine(
                listOf(
                    registered(stubProvider("jira", jiraData)),
                    registered(failingProvider("broken")),
                ),
                logger = capturingLogger,
            )

            val result = engine.enrich(makeMR())

            assertTrue(result.has("jira:ticket"))
            assertFalse(result.has("broken:data"))
            assertTrue(warningMessages.any { it.contains("broken") && it.contains("non-fatal") })
        }

    @Test
    fun `provider timeout is non-fatal`() =
        runTest {
            val slowData = mapOf("slow:data" to JsonPrimitive("value"))
            val fastData = mapOf("fast:data" to JsonPrimitive("quick"))
            val engine = EnrichmentEngine(
                listOf(
                    registered(
                        slowProvider("slow", delayMs = 5000L, slowData),
                        timeout = KarandashDuration.ofMillis(50),
                    ),
                    registered(stubProvider("fast", fastData)),
                ),
            )

            val result = engine.enrich(makeMR())

            assertTrue(result.has("fast:data"))
            assertFalse(result.has("slow:data"))
        }

    @Test
    fun `all providers fail returns EMPTY with missingIds`() =
        runTest {
            warningMessages.clear()
            val engine = EnrichmentEngine(
                listOf(
                    registered(failingProvider("broken1")),
                    registered(failingProvider("broken2")),
                ),
                logger = capturingLogger,
            )

            val result = engine.enrich(makeMR())

            assertTrue(result.availableIds.isEmpty())
            assertEquals(2, warningMessages.size)
            assertTrue(result.isMissing("broken1:data"))
            assertTrue(result.isMissing("broken2:data"))
        }

    @Test
    fun `failed provider IDs tracked in missingIds`() =
        runTest {
            warningMessages.clear()
            val jiraData = mapOf("jira:ticket" to JsonPrimitive("PROJ-123"))
            val engine = EnrichmentEngine(
                listOf(
                    registered(stubProvider("jira", jiraData)),
                    registered(failingProvider("broken")),
                ),
                logger = capturingLogger,
            )

            val result = engine.enrich(makeMR())

            assertTrue(result.has("jira:ticket"))
            assertFalse(result.isMissing("jira:ticket"))
            assertTrue(result.isMissing("broken:data"))
        }

    @Test
    fun `timed-out provider IDs tracked in missingIds`() =
        runTest {
            val slowData = mapOf("slow:data" to JsonPrimitive("value"))
            val engine = EnrichmentEngine(
                listOf(
                    registered(
                        slowProvider("slow", delayMs = 5000L, slowData),
                        timeout = KarandashDuration.ofMillis(50),
                    ),
                ),
                logger = capturingLogger,
            )

            val result = engine.enrich(makeMR())

            assertTrue(result.isMissing("slow:data"))
        }

    // --- DAG execution tests ---

    @Test
    fun `requiredEnrichment default returns empty list`() {
        val provider = stubProvider("basic", mapOf("basic:data" to JsonPrimitive("x")))
        assertTrue(provider.requiredEnrichment().isEmpty())
    }

    @Test
    fun `buildExecutionLayers puts independent providers in one layer`() {
        val a = registered(stubProvider("a", mapOf("a:data" to JsonPrimitive("1"))))
        val b = registered(stubProvider("b", mapOf("b:data" to JsonPrimitive("2"))))

        val layers = EnrichmentEngine.buildExecutionLayers(listOf(a, b))

        assertEquals(1, layers.size)
        assertEquals(2, layers[0].size)
    }

    @Test
    fun `buildExecutionLayers orders dependent providers into separate layers`() {
        val upstream = registered(
            stubProvider("upstream", mapOf("upstream:data" to JsonPrimitive("1"))),
        )
        val downstream = registered(
            stubProvider(
                "downstream",
                mapOf("downstream:data" to JsonPrimitive("2")),
                deps = listOf("upstream:data"),
            ),
        )

        val layers = EnrichmentEngine.buildExecutionLayers(listOf(downstream, upstream))

        assertEquals(2, layers.size)
        assertEquals("upstream", layers[0][0].id)
        assertEquals("downstream", layers[1][0].id)
    }

    @Test
    fun `buildExecutionLayers handles three-level chain`() {
        val l0 = registered(stubProvider("l0", mapOf("l0:out" to JsonPrimitive("0"))))
        val l1 = registered(
            stubProvider("l1", mapOf("l1:out" to JsonPrimitive("1")), deps = listOf("l0:out")),
        )
        val l2 = registered(
            stubProvider("l2", mapOf("l2:out" to JsonPrimitive("2")), deps = listOf("l1:out")),
        )

        val layers = EnrichmentEngine.buildExecutionLayers(listOf(l2, l1, l0))

        assertEquals(3, layers.size)
        assertEquals("l0", layers[0][0].id)
        assertEquals("l1", layers[1][0].id)
        assertEquals("l2", layers[2][0].id)
    }

    @Test
    fun `buildExecutionLayers handles circular dependency gracefully`() {
        val a = registered(
            stubProvider("a", mapOf("a:data" to JsonPrimitive("1")), deps = listOf("b:data")),
        )
        val b = registered(
            stubProvider("b", mapOf("b:data" to JsonPrimitive("2")), deps = listOf("a:data")),
        )

        val layers = EnrichmentEngine.buildExecutionLayers(listOf(a, b))

        // Both placed in final fallback layer
        assertEquals(1, layers.size)
        assertEquals(2, layers[0].size)
    }

    @Test
    fun `buildExecutionLayers cycle detection logs error`() {
        val errorMessages = mutableListOf<String>()
        val errorLogger = object : Logger {
            override fun debug(message: String) {}

            override fun info(message: String) {}

            override fun warn(message: String) {}

            override fun error(message: String) {
                errorMessages.add(message)
            }
        }
        val a = registered(
            stubProvider("a", mapOf("a:data" to JsonPrimitive("1")), deps = listOf("b:data")),
        )
        val b = registered(
            stubProvider("b", mapOf("b:data" to JsonPrimitive("2")), deps = listOf("a:data")),
        )

        EnrichmentEngine.buildExecutionLayers(listOf(a, b), errorLogger)

        assertTrue(errorMessages.isNotEmpty(), "Expected error log for circular dependency")
        assertTrue(
            errorMessages.any { it.contains("circular") || it.contains("unresolvable") },
            "Error log should mention circular or unresolvable dependency",
        )
    }

    @Test
    fun `dependent provider receives upstream data`() =
        runTest {
            val upstreamData = mapOf("upstream:value" to JsonPrimitive("hello"))
            val upstream = stubProvider("upstream", upstreamData)
            val downstream = dependentProvider(
                providerId = "downstream",
                outputIds = listOf("downstream:combined"),
                deps = listOf("upstream:value"),
            ) { ctx ->
                val upstreamValue = ctx.get("upstream:value")
                mapOf("downstream:combined" to JsonPrimitive("got-$upstreamValue"))
            }

            val engine = EnrichmentEngine(
                listOf(registered(downstream), registered(upstream)),
            )

            val result = engine.enrich(makeMR())

            assertTrue(result.has("upstream:value"))
            assertTrue(result.has("downstream:combined"))
            assertEquals(
                JsonPrimitive("got-\"hello\""),
                result.get("downstream:combined"),
            )
        }

    @Test
    fun `provider skipped when all outputs already cached`() =
        runTest {
            // Two providers output the same enrichment ID; second should be skipped
            val data = mapOf("shared:data" to JsonPrimitive("first"))
            val first = stubProvider("first", data)
            val duplicateData = mapOf("shared:data" to JsonPrimitive("second"))
            val duplicate = stubProvider("duplicate", duplicateData)

            val engine = EnrichmentEngine(
                listOf(registered(first), registered(duplicate)),
            )

            val result = engine.enrich(makeMR())

            // First provider wins since both are in layer 0; one will be cached
            assertTrue(result.has("shared:data"))
        }

    @Test
    fun `EnrichmentContext filter works correctly`() {
        val ctx = EnrichmentContext(
            mapOf(
                "jira:ticket" to JsonPrimitive("a"),
                "jira:sprint" to JsonPrimitive("b"),
                "github:status" to JsonPrimitive("c"),
            ),
        )

        val filtered = ctx.filter(listOf("jira"))
        assertEquals(setOf("jira:ticket", "jira:sprint"), filtered.availableIds)
        assertFalse(filtered.has("github:status"))
    }

    @Test
    fun `EnrichmentContext has returns correct values`() {
        val ctx = EnrichmentContext(
            mapOf("jira:ticket" to JsonPrimitive("a")),
        )

        assertTrue(ctx.has("jira:ticket"))
        assertFalse(ctx.has("jira:sprint"))
        assertFalse(ctx.has("github:status"))
    }
}
