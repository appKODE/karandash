package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.loader.BridgeDeserializer
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared [AiContextEnricherModule] implementation using [BridgeTransport].
 */
internal class BridgeAiContextEnricherModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    AiContextEnricherModule {
    override val type: ModuleType get() = ModuleType.AI_CONTEXT_ENRICHER
    private val json = KarandashJson.Default

    override val enricherPriority: Int get() = transport.getIntProperty(PROP_ENRICHER_PRIORITY, DEFAULT_PRIORITY)

    override suspend fun enrich(
        mr: MergeRequest,
        context: AiContextEnricherContext,
    ): Map<String, String> {
        if (!transport.hasCapability(METHOD_ENRICH)) return emptyMap()
        return transport.guarded(METHOD_ENRICH, emptyMap()) {
            val mrJson = json.encodeToString(MergeRequest.serializer(), mr)
            val contextJson = buildJsonObject {
                put("ruleId", context.ruleId)
            }.toString()
            val result = transport.call(METHOD_ENRICH, mrJson, contextJson)
                ?: return emptyMap()
            BridgeDeserializer.parseStringMap(result)
        }
    }

    override suspend fun provideBlock(
        mr: MergeRequest,
        changes: List<FileChange>,
        config: JsonElement,
        budget: BlockBudget,
    ): AiContextBlock? {
        if (!transport.hasCapability(METHOD_PROVIDE_BLOCK)) return null
        return transport.guarded(METHOD_PROVIDE_BLOCK, null) {
            val mrJson = json.encodeToString(MergeRequest.serializer(), mr)
            val changesJson = json.encodeToString(ListSerializer(FileChange.serializer()), changes)
            val configJson = json.encodeToString(JsonElement.serializer(), config)
            val budgetJson = json.encodeToString(BlockBudget.serializer(), budget)
            val result = transport.call(METHOD_PROVIDE_BLOCK, mrJson, changesJson, configJson, budgetJson)
                ?: return null
            if (result.isBlank()) return null
            json.decodeFromString(AiContextBlock.serializer(), result)
        }
    }

    internal companion object {
        const val PROP_ENRICHER_PRIORITY = "enricherPriority"
        const val METHOD_ENRICH = "aiEnrichContext"
        const val METHOD_PROVIDE_BLOCK = "aiProvideBlock"
        private const val DEFAULT_PRIORITY = 100
    }
}
