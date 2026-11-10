package io.karandash.contracts.ai

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Enrichment provider data travels provider -> per-rule scope -> analyzer -> outgoing LLM request for the
 * default review, and an enricher whose enrichment ids were not delivered (provider disabled, or the rule not
 * entitled to them) is not called.
 */
class AiEnrichmentReachesPromptContractTest {
    private var log = ""

    private suspend fun requestFor(
        modules: List<io.karandash.api.Module>,
        topLevelYaml: String = "",
    ): String {
        val harness = AiReviewHarness(extraModules = modules)
        harness.run(topLevelYaml = topLevelYaml)
        assertTrue(harness.requestBodies.isNotEmpty(), "setup: no LLM request reached the MockEngine")
        log = harness.logger.messages.joinToString("\n")
        return harness.allRequests
    }

    @Test
    fun `default review gets ticket CI and symbol context from an enabled provider`() =
        runTest(timeout = 2.minutes) {
            val body = requestFor(listOf(FakeProvider()))

            assertTrue(body.contains(TICKET_MARKER), "ticket context missing from the review request\n$log")
            assertTrue(body.contains(CI_MARKER), "CI context missing from the review request")
            assertTrue(body.contains(SYMBOL_MARKER), "symbol context missing from the review request")
        }

    @Test
    fun `a disabled provider puts no context in the review request`() =
        runTest(timeout = 2.minutes) {
            val body = requestFor(listOf(FakeProvider()), topLevelYaml = "enrichment:\n  $PROVIDER_ID: false")

            assertFalse(body.contains(TICKET_MARKER), "disabled provider's ticket data reached the request")
            assertFalse(body.contains(CI_MARKER), "disabled provider's CI data reached the request")
        }

    @Test
    fun `an enricher without its enrichment data is not called`() =
        runTest(timeout = 2.minutes) {
            val body = requestFor(listOf(HotspotEnricher()))

            assertFalse(body.contains(HOTSPOT_MARKER), "an enricher whose ids were not delivered still ran")
        }

    @Test
    fun `an enricher with its enrichment data reaches the review request`() =
        runTest(timeout = 2.minutes) {
            val body = requestFor(listOf(FakeProvider(), HotspotEnricher()))

            assertTrue(body.contains(HOTSPOT_MARKER), "an enricher with delivered data was not called\n$log")
        }

    private class FakeProvider : EnrichmentProviderModule {
        override val id: String = PROVIDER_ID
        override val name: String = "Fake context provider"
        override val version: String = "1.0"
        override val description: String = ""
        override val dslName: String = PROVIDER_ID
        override val configKey: String = PROVIDER_ID
        override val defaultEnabled: Boolean = true
        override val defaultPriority: Int = 100

        @OptIn(ExperimentalKarandashApi::class)
        override val providedEnrichment: Set<String> =
            setOf(
                EnrichmentKeys.JIRA_TICKETS,
                EnrichmentKeys.CI_FAILED_JOBS,
                EnrichmentKeys.SYMBOLS_CHANGED,
                EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY,
            )

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun enrich(
            mr: MergeRequest,
            config: JsonElement,
            upstreamContext: EnrichmentContext,
        ): Map<String, JsonElement> =
            mapOf(
                EnrichmentKeys.JIRA_TICKETS to buildJsonObject {
                    put("PROJ-7", buildJsonObject { put("summary", TICKET_MARKER) })
                },
                EnrichmentKeys.CI_FAILED_JOBS to buildJsonArray {
                    add(buildJsonObject { put("name", CI_MARKER) })
                },
                EnrichmentKeys.SYMBOLS_CHANGED to buildJsonObject {
                    put(
                        "src/A.kt",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("kind", "function")
                                    put("name", SYMBOL_MARKER)
                                },
                            )
                        },
                    )
                },
                EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY to buildJsonObject { put("src/A.kt", 3) },
            )
    }

    /** Ignores its config and always answers, like an enricher with a local fallback. */
    private class HotspotEnricher : AiContextEnricherModule {
        override val id: String = "test-hotspot-enricher"
        override val name: String = "Test hotspots"
        override val version: String = "1.0"

        override fun handledEnrichmentIds(): List<String> = listOf(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun enrich(
            mr: MergeRequest,
            context: AiContextEnricherContext,
        ): Map<String, String> = emptyMap()

        override suspend fun provideBlock(
            mr: MergeRequest,
            changes: List<FileChange>,
            config: JsonElement,
            budget: BlockBudget,
        ): AiContextBlock = AiContextBlock("#### Hotspots", HOTSPOT_MARKER, priority = 100, source = id)
    }

    private companion object {
        const val PROVIDER_ID = "fake-context"
        const val TICKET_MARKER = "TICKET-MARKER login must lock after five tries"
        const val CI_MARKER = "CI-MARKER-unit-tests"
        const val SYMBOL_MARKER = "SYMBOL-MARKER-risky"
        const val HOTSPOT_MARKER = "HOTSPOT-MARKER src/A.kt"
    }
}
