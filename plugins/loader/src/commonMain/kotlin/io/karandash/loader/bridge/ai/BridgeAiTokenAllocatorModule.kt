package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiTokenAllocatorModule
import io.karandash.loader.BridgeDeserializer
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Shared [AiTokenAllocatorModule] implementation using [BridgeTransport].
 */
internal class BridgeAiTokenAllocatorModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    AiTokenAllocatorModule {
    override val type: ModuleType get() = ModuleType.AI_TOKEN_ALLOCATOR
    private val json = KarandashJson.Default

    override suspend fun allocate(
        rules: Set<String>,
        totalBudget: Int,
        priorities: Map<String, Int>,
    ): Map<String, Int> {
        if (!transport.hasCapability(METHOD_ALLOCATE)) return emptyMap()
        return transport.guarded(METHOD_ALLOCATE, emptyMap()) {
            val rulesJson = json.encodeToString(SetSerializer(String.serializer()), rules)
            val prioritiesJson = json.encodeToString(
                MapSerializer(String.serializer(), Int.serializer()),
                priorities,
            )
            val result = transport.call(METHOD_ALLOCATE, rulesJson, totalBudget.toString(), prioritiesJson)
                ?: return emptyMap()
            BridgeDeserializer.parseIntMap(result)
        }
    }

    private companion object {
        const val METHOD_ALLOCATE = "aiAllocateTokens"
    }
}
