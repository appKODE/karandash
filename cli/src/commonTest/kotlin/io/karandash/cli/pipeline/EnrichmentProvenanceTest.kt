package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.finding.EnricherProvenance
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
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Enrichers that are silently off (missing token, no binary) show in provenance as skipped with their reason. */
class EnrichmentProvenanceTest {
    private class Stub(
        override val id: String,
        private val initialized: Boolean = true,
        private var reason: String? = null,
        private val data: String? = "0123456789abcdef",
        private val initError: String? = null,
        private val empty: Boolean = false,
        private val reasonAfterEnrich: String? = null,
    ) : EnrichmentProviderModule {
        override val name = id
        override val version = "1.0"
        override val dslName = id
        override val providedEnrichment = setOf("$id:data")

        override suspend fun initialize(context: PluginContext): Boolean {
            initError?.let { error(it) }
            return initialized
        }

        override fun skipReason(): String? = reason

        override suspend fun enrich(
            mr: MergeRequest,
            config: JsonElement,
            upstreamContext: EnrichmentContext,
        ): Map<String, JsonElement> {
            reasonAfterEnrich?.let { reason = it }
            return if (empty) emptyMap() else mapOf("$id:data" to JsonPrimitive(checkNotNull(data) { "boom" }))
        }
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

    private suspend fun enrichers(vararg providers: Stub): List<EnricherProvenance> {
        val registry = ModuleRegistryImpl()
        providers.forEach { registry.register(it) }
        val stage = ConfigLoadStage(
            config = Config(),
            configFilePath = null,
            usingZeroConfig = false,
            registry = registry,
            bootstrap = PluginBootstrap(),
        )
        return executeEnrichmentProviders(stage, mr, logger = NoOpLogger).enrichers.sortedBy { it.id }
    }

    @Test
    fun `an enricher with a skip reason is skipped with it while a working one is ok`() =
        runTest {
            val result = enrichers(
                Stub("a-ok"),
                Stub("b-jira", reason = "JIRA_TOKEN not set"),
                Stub("c-symbols", initialized = false, reason = "ast-grep not found"),
            )

            assertEquals(
                listOf(
                    EnricherProvenance("a-ok", ProvenanceStatus.OK, items = 1),
                    EnricherProvenance("b-jira", ProvenanceStatus.SKIPPED, reason = "JIRA_TOKEN not set"),
                    EnricherProvenance("c-symbols", ProvenanceStatus.SKIPPED, reason = "ast-grep not found"),
                ),
                result,
            )
        }

    @Test
    fun `an ok enricher counts the ids it delivered`() =
        runTest {
            assertEquals(listOf(1, 0), enrichers(Stub("a-ok"), Stub("b-empty", empty = true)).map { it.items })
        }

    @Test
    fun `an enricher that learns while enriching why it found nothing is skipped with that reason`() =
        runTest {
            val result = enrichers(Stub("a-git", empty = true, reasonAfterEnrich = "no data: git not available")).single()

            assertEquals(EnricherProvenance("a-git", ProvenanceStatus.SKIPPED, reason = "no data: git not available"), result)
        }

    @Test
    fun `an enricher turned off by its own config without a reason is left out`() =
        runTest {
            assertEquals(listOf("a-ok"), enrichers(Stub("a-ok"), Stub("b-off", initialized = false)).map { it.id })
        }

    @Test
    fun `an enricher whose enrich throws is failed`() =
        runTest {
            val result = enrichers(Stub("a-boom", data = null)).single()

            assertEquals(ProvenanceStatus.FAILED, result.status)
            assertEquals("a-boom", result.id)
        }

    @Test
    fun `no enrichers means no provenance rows`() =
        runTest {
            assertEquals(emptyList(), enrichers())
        }

    @Test
    fun `an init failure message never reaches the provenance reason`() =
        runTest {
            val secret = "https://jira.example.com/rest?token=s3cr3t-value"
            val result = enrichers(Stub("a-jira", initError = "connect to $secret failed")).single()

            assertEquals(EnricherProvenance("a-jira", ProvenanceStatus.FAILED, reason = "initialize failed, see the job log"), result)
            assertFalse(secret in result.toString())
        }

    @Test
    fun `an enricher with a skip reason that then fails is failed not skipped`() =
        runTest {
            val result = enrichers(Stub("a-ci", reason = "no pipeline API", data = null)).single()

            assertEquals(ProvenanceStatus.FAILED, result.status)
        }

    @Test
    fun `an enricher row is the same for two runs whose data differs only in a timestamp`() =
        runTest {
            val first = enrichers(Stub("a-ci", data = "pipeline fetched at 2026-10-22T10:00:00Z"))
            val second = enrichers(Stub("a-ci", data = "pipeline fetched at 2026-10-22T10:00:00.123456789Z"))

            assertEquals(first, second)
        }
}
