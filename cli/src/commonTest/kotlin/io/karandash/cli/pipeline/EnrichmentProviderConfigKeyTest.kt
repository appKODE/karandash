package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.config.Config
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An `enrichment:` block reaches its provider under the documented configKey and under the module id. */
class EnrichmentProviderConfigKeyTest {
    private class StubProvider : EnrichmentProviderModule {
        override val id = "stub-enrichment"
        override val name = "Stub"
        override val version = "1.0"
        override val dslName = "stub"
        override val configKey = "stub-key"
        override val defaultEnabled = false
        override val providedEnrichment = setOf(DATA_ID)

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun enrich(
            mr: MergeRequest,
            config: JsonElement,
            upstreamContext: EnrichmentContext,
        ): Map<String, JsonElement> = mapOf(DATA_ID to JsonPrimitive("ran"))
    }

    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = null,
        author = User(username = "dev", name = "Dev"),
        sourceBranch = "feature",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        changes = emptyList(),
        commits = emptyList(),
    )

    private suspend fun enrichWith(blocks: Map<String, JsonElement>): EnrichmentContext {
        val registry = ModuleRegistryImpl()
        registry.register(StubProvider())
        val stage = ConfigLoadStage(
            config = Config(enrichmentProviders = blocks),
            configFilePath = null,
            usingZeroConfig = false,
            registry = registry,
            bootstrap = PluginBootstrap(),
        )
        return executeEnrichmentProviders(stage, mr, logger = NoOpLogger).context
    }

    @Test
    fun `block under the configKey enables the provider`() =
        runTest {
            // keys arrive camelCased from the parser: `stub-key:` becomes `stubKey`
            val ctx = enrichWith(mapOf("stubKey" to JsonObject(emptyMap())))
            assertEquals(JsonPrimitive("ran"), ctx.get(DATA_ID))
        }

    @Test
    fun `block under the module id still enables the provider`() =
        runTest {
            val ctx = enrichWith(mapOf("stubEnrichment" to JsonObject(emptyMap())))
            assertEquals(JsonPrimitive("ran"), ctx.get(DATA_ID))
        }

    @Test
    fun `no block leaves a default-disabled provider off`() =
        runTest {
            assertTrue(enrichWith(emptyMap()).get(DATA_ID) == null)
        }

    private companion object {
        const val DATA_ID = "stub-key:data"
    }
}
