package io.karandash.plugins.ai.core.dispatch

import io.karandash.api.ai.AiContextEnricherModule

/**
 * Dispatches enrichment IDs to their registered [AiContextEnricherModule] formatters.
 *
 * Built at assembler startup from the list of registered enrichers. Each enricher
 * declares the IDs it handles via [AiContextEnricherModule.handledEnrichmentIds].
 * When two enrichers declare the same ID, lower [AiContextEnricherModule.enricherPriority]
 * wins (lower number = higher priority). A log warning is emitted for every clash.
 *
 * @param enrichers All registered enrichers for the current pipeline run.
 * @param onClash Called when two enrichers claim the same enrichment ID; receives
 *   the ID, the winner, and the loser. Defaults to a no-op.
 */
class AiEnricherDispatcher(
    enrichers: List<AiContextEnricherModule>,
    onClash: (id: String, winner: AiContextEnricherModule, loser: AiContextEnricherModule) -> Unit = { _, _, _ -> },
) {
    private val index: Map<String, AiContextEnricherModule> = buildIndex(enrichers, onClash)

    /**
     * Returns the enricher registered to handle [enrichmentId], or null if none declared it.
     */
    fun enricherFor(enrichmentId: String): AiContextEnricherModule? = index[enrichmentId]

    /** All enrichment IDs that have a registered formatter. */
    val handledIds: Set<String> get() = index.keys

    companion object {
        private fun buildIndex(
            enrichers: List<AiContextEnricherModule>,
            onClash: (String, AiContextEnricherModule, AiContextEnricherModule) -> Unit,
        ): Map<String, AiContextEnricherModule> {
            val sorted = enrichers.sortedBy { it.enricherPriority }
            val result = mutableMapOf<String, AiContextEnricherModule>()
            for (enricher in sorted) {
                for (id in enricher.handledEnrichmentIds()) {
                    val existing = result[id]
                    if (existing == null) {
                        result[id] = enricher
                    } else {
                        onClash(id, existing, enricher)
                    }
                }
            }
            return result
        }
    }
}
