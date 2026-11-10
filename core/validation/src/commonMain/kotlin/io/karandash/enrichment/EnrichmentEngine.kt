@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.enrichment

import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.duration.KarandashDuration
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

/**
 * Orchestrates enrichment providers using dependency-aware DAG execution.
 *
 * Providers declare dependencies via [EnrichmentProviderModule.requiredEnrichment].
 * The engine builds execution layers via topological sort:
 * - Layer 0: providers with no dependencies
 * - Layer 1: providers whose dependencies are all satisfied by layer 0
 * - etc.
 *
 * Providers within each layer run in parallel. Accumulated results are passed
 * to subsequent layers via [EnrichmentContext]. Provider failures are non-fatal:
 * logged at warn level and tracked in [EnrichmentContext.missingIds] so
 * downstream providers and rules can degrade gracefully.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
class EnrichmentEngine(
    private val providers: List<RegisteredEnrichmentProvider>,
    private val logger: Logger = NoOpLogger,
) {
    /** Per provider of the last [enrich] call: how long it took, or that its outputs were cached, for the diagnostics. */
    var timings: List<EnrichmentTiming> = emptyList()
        private set

    /**
     * Runs all registered enrichment providers in dependency order and merges results.
     *
     * @param mr The merge request to enrich.
     * @return An [EnrichmentContext] containing all successfully collected data
     *         and tracking IDs of providers that failed.
     */
    suspend fun enrich(mr: MergeRequest): EnrichmentContext {
        if (providers.isEmpty()) return EnrichmentContext.EMPTY

        val layers = buildExecutionLayers(providers, logger)
        val accumulated = mutableMapOf<String, JsonElement>()
        val missingIds = mutableSetOf<String>()
        val ran = mutableListOf<EnrichmentTiming>()

        for (layer in layers) {
            val upstreamContext = EnrichmentContext.withMissing(accumulated.toMap(), missingIds.toSet())
            val layerResults = executeLayer(layer, mr, upstreamContext)

            for (result in layerResults) {
                ran += result.timing
                val data = result.data
                if (data != null) {
                    accumulated.putAll(data)
                } else {
                    missingIds.addAll(result.failedIds)
                }
            }
        }

        timings = ran
        return EnrichmentContext.withMissing(accumulated.toMap(), missingIds.toSet())
    }

    private suspend fun executeLayer(
        layer: List<RegisteredEnrichmentProvider>,
        mr: MergeRequest,
        upstreamContext: EnrichmentContext,
    ): List<ProviderResult> =
        coroutineScope {
            val (cached, pending) = layer.partition { allOutputsCached(it, upstreamContext) }
            cached.forEach { logger.debug("[${it.id}] enrich: skipped (reason=outputs-cached)") }
            cached.map { ProviderResult(emptyMap(), emptyList(), EnrichmentTiming(it.id, 0, fromCache = true)) } +
                pending
                    .map { provider ->
                        async { executeProvider(provider, mr, upstreamContext) }
                    }.awaitAll()
        }

    @Suppress("TooGenericExceptionCaught") // boundary: enrichment provider execution may throw any exception
    private suspend fun executeProvider(
        provider: RegisteredEnrichmentProvider,
        mr: MergeRequest,
        upstreamContext: EnrichmentContext,
    ): ProviderResult {
        val started = TimeSource.Monotonic.markNow()

        fun timing(data: Map<String, JsonElement>?) =
            EnrichmentTiming(provider.id, started.elapsedNow().inWholeMilliseconds, outputIds = data?.keys?.sorted().orEmpty())
        return try {
            val (data, elapsed) = measureTimedValue {
                withTimeoutOrNull(provider.timeout.millis) {
                    provider.module.enrich(mr, provider.configElement, upstreamContext)
                }
            }
            if (data != null) {
                logger.debug("[${provider.id}] enrich: ok (keys=${data.size}, ms=${elapsed.inWholeMilliseconds})")
                ProviderResult(data = data, failedIds = emptyList(), timing = timing(data))
            } else {
                logger.warn("Enrichment '${provider.id}' timed out (non-fatal)")
                ProviderResult(data = null, failedIds = provider.module.providedEnrichment.toList(), timing = timing(null))
            }
        } catch (e: Exception) {
            // boundary: enrichment provider execution
            e.rethrowCancellation()
            logger.warn("Enrichment '${provider.id}' failed (non-fatal): ${e.message}")
            ProviderResult(data = null, failedIds = provider.module.providedEnrichment.toList(), timing = timing(null))
        }
    }

    companion object {
        /**
         * Builds topologically sorted execution layers from provider dependencies.
         *
         * Each layer contains providers whose [EnrichmentProviderModule.requiredEnrichment]
         * are fully satisfied by providers in earlier layers. Providers within a layer
         * have no mutual dependencies and can execute in parallel.
         *
         * Circular dependencies or unresolvable requirements are placed in a final
         * fallback layer with a warning logged.
         */
        internal fun buildExecutionLayers(
            providers: List<RegisteredEnrichmentProvider>,
            logger: Logger = NoOpLogger,
        ): List<List<RegisteredEnrichmentProvider>> {
            val resolved = mutableSetOf<String>()
            val remaining = providers.toMutableList()
            val layers = mutableListOf<List<RegisteredEnrichmentProvider>>()

            while (remaining.isNotEmpty()) {
                val layer = remaining.filter { provider ->
                    provider.module.requiredEnrichment().all { it in resolved }
                }
                if (layer.isEmpty()) {
                    // Circular dependency or unresolvable - add remaining as final layer
                    val ids = remaining.joinToString(", ") { it.module.id }
                    logger.error(
                        "Enrichment: circular or unresolvable dependency detected; " +
                            "affected providers: $ids"
                    )
                    layers.add(remaining.toList())
                    break
                }
                layers.add(layer)
                remaining.removeAll(layer.toSet())
                for (provider in layer) {
                    resolved.addAll(provider.module.providedEnrichment)
                }
            }
            return layers
        }

        private fun allOutputsCached(
            provider: RegisteredEnrichmentProvider,
            context: EnrichmentContext,
        ): Boolean = provider.module.providedEnrichment.all { context.has(it) }
    }
}

/**
 * Result of a single provider execution.
 *
 * @param data Enrichment data if successful, null on failure/timeout.
 * @param failedIds Enrichment IDs that this provider would have contributed.
 *        Non-empty only when [data] is null.
 */
private class ProviderResult(
    val data: Map<String, JsonElement>?,
    val failedIds: List<String>,
    val timing: EnrichmentTiming,
)

/** How one enrichment provider went in a run: its time, whether its outputs came from the cache, the ids it produced. */
@Serializable
data class EnrichmentTiming(
    val id: String,
    val durationMs: Long,
    val fromCache: Boolean = false,
    val outputIds: List<String> = emptyList(),
)

/**
 * A registered enrichment provider with its resolved configuration.
 *
 * @param id Provider identifier for logging and diagnostics.
 * @param module The enrichment provider module instance.
 * @param configElement Provider-specific configuration as [JsonElement].
 * @param timeout Maximum time for the [EnrichmentProviderModule.enrich] call.
 */
@Serializable
data class RegisteredEnrichmentProvider(
    val id: String,
    val module: EnrichmentProviderModule,
    val configElement: JsonElement,
    val timeout: KarandashDuration,
)
