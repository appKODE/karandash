package io.karandash.api.context

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * Provides access to enrichment data from configured enrichment providers.
 *
 * Each entry is keyed by an enrichment ID (convention: `"provider-id:data-type"`)
 * and holds serialized data as [JsonElement]. Rules declare which enrichment IDs
 * they consume; the pipeline filters the context per-rule before invocation.
 *
 * Thread-safe: the backing map is read-only after construction.
 *
 * @param data Enrichment data keyed by enrichment ID.
 * @param missingIds Set of enrichment IDs that were requested but the provider
 *        failed or timed out. Rules can check [isMissing] to degrade gracefully.
 */
public class EnrichmentContext(
    private val data: Map<String, JsonElement>,
    public val missingIds: Set<String> = emptySet(),
) {
    /** Returns raw [JsonElement] for the given enrichment ID, or null if absent. */
    public fun get(enrichmentId: String): JsonElement? = data[enrichmentId]

    /**
     * Deserializes the enrichment data for [enrichmentId] into [T].
     *
     * Returns null if the enrichment ID is absent or deserialization fails.
     * Uses [KarandashJson.Default] for decoding.
     */
    public inline fun <reified T> getTyped(enrichmentId: String): T? {
        val element = get(enrichmentId) ?: return null
        return try {
            KarandashJson.Default.decodeFromJsonElement(serializer<T>(), element)
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Returns true if data for [enrichmentId] is available. */
    public fun has(enrichmentId: String): Boolean = enrichmentId in data

    /**
     * Returns true if the given enrichment ID was requested but the provider failed.
     * Rules and downstream providers can use this to degrade gracefully instead of
     * assuming the data simply wasn't configured.
     */
    public fun isMissing(enrichmentId: String): Boolean = enrichmentId in missingIds

    /** All available enrichment IDs in this context. */
    public val availableIds: Set<String> get() = data.keys

    /**
     * Returns a new [EnrichmentContext] containing only entries whose IDs
     * start with one of the given [enrichmentIds] prefixes.
     *
     * This enables per-rule filtering: a rule declaring `enrichment: ["jira"]`
     * receives only entries like `"jira:ticket-details"`, `"jira:sprint"`, etc.
     */
    public fun filter(enrichmentIds: List<String>): EnrichmentContext =
        EnrichmentContext(
            data = data.filterKeys { id ->
                enrichmentIds.any { prefix -> id.startsWith(prefix) }
            },
            missingIds = missingIds
                .filter { id ->
                    enrichmentIds.any { prefix -> id.startsWith(prefix) }
                }.toSet(),
        )

    /**
     * Returns a new [EnrichmentContext] containing only entries whose ID is exactly one of [ids].
     * Callers with glob/prefix patterns should resolve them against [availableIds] first (e.g. via
     * `EnrichmentGlobMatcher` in `core/glob`) and pass the resolved ID set here -- this class has
     * no glob-matching logic of its own, to stay dependency-free at the base of the module graph.
     */
    public fun filterIds(ids: Set<String>): EnrichmentContext =
        EnrichmentContext(
            data = data.filterKeys { it in ids },
            missingIds = missingIds.filter { it in ids }.toSet(),
        )

    public companion object {
        /** Empty context with no enrichment data. */
        public val EMPTY: EnrichmentContext = EnrichmentContext(data = emptyMap())

        /**
         * Creates a context with both successful data and tracked failures.
         *
         * @param data Successfully collected enrichment data.
         * @param missingIds Enrichment IDs that were requested but failed to load.
         */
        public fun withMissing(
            data: Map<String, JsonElement>,
            missingIds: Set<String>,
        ): EnrichmentContext = EnrichmentContext(data = data, missingIds = missingIds)
    }
}
