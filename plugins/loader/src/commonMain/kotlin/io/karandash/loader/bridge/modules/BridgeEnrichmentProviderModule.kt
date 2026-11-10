package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.support.suspendCatching
import io.karandash.loader.BridgeDeserializer
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonElement

/**
 * Shared [EnrichmentProviderModule] implementation using [BridgeTransport].
 *
 * Bridges JS/WASM/native plugins that declare type `enrichment-provider`.
 * Missing methods (no capability) degrade gracefully: [enrichmentIds] returns
 * an empty list and [enrich] returns an empty map.
 */
internal class BridgeEnrichmentProviderModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    EnrichmentProviderModule {
    override val type: ModuleType get() = ModuleType.ENRICHMENT_PROVIDER
    private val json = KarandashJson.Default

    override val providedEnrichment: Set<String>
        get() {
            if (!transport.hasCapability(METHOD_ENRICHMENT_IDS)) return emptySet()
            val result = transport.getProperty(METHOD_ENRICHMENT_IDS)
            if (result.isBlank()) return emptySet()
            return suspendCatching {
                json.decodeFromString<List<String>>(result).toSet()
            }.getOrDefault(emptySet())
        }

    override suspend fun enrich(
        mr: MergeRequest,
        config: JsonElement,
        upstreamContext: EnrichmentContext,
    ): Map<String, JsonElement> {
        if (!transport.hasCapability(METHOD_ENRICH)) return emptyMap()
        return transport.guarded(METHOD_ENRICH, emptyMap()) {
            val mrJson = json.encodeToString(MergeRequest.serializer(), mr)
            val result = transport.call(METHOD_ENRICH, mrJson, config.toString())
                ?: return emptyMap()
            BridgeDeserializer.parseJsonElementMap(result)
        }
    }

    private companion object {
        const val METHOD_ENRICHMENT_IDS = "enrichmentIds"
        const val METHOD_ENRICH = "enrich"
    }
}
