package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.finding.ProvenanceStatus
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
import kotlin.test.Test
import kotlin.test.assertEquals

/** a configured enricher whose initialize returns false shows up as skipped, not as missing. */
class EnrichmentSkippedRegressionTest {
    private class Refusing(
        override val id: String,
    ) : EnrichmentProviderModule {
        override val name = id
        override val version = "1.0"
        override val dslName = id
        override val providedEnrichment = setOf("$id:data")

        override suspend fun initialize(context: PluginContext): Boolean = false

        override suspend fun enrich(
            mr: MergeRequest,
            config: JsonElement,
            upstreamContext: EnrichmentContext,
        ): Map<String, JsonElement> = emptyMap()
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

    private suspend fun run(config: Config) =
        executeEnrichmentProviders(
            ConfigLoadStage(
                config = config,
                configFilePath = null,
                usingZeroConfig = false,
                registry = ModuleRegistryImpl().also { it.register(Refusing("a-conf")) },
                bootstrap = PluginBootstrap(),
            ),
            mr,
            logger = NoOpLogger,
        ).enrichers

    @Test
    fun `a configured enricher that refuses to start is skipped with a reason`() =
        runTest {
            val rows = run(Config(enrichmentProviders = mapOf("a-conf" to JsonObject(emptyMap()))))

            assertEquals(listOf("a-conf"), rows.map { it.id })
            assertEquals(ProvenanceStatus.SKIPPED, rows.single().status)
        }
}
