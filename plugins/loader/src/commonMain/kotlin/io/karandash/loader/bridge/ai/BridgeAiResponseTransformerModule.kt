package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiResponseTransformerContext
import io.karandash.api.ai.AiResponseTransformerModule
import io.karandash.loader.BridgeDeserializer
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared [AiResponseTransformerModule] implementation using [BridgeTransport].
 */
internal class BridgeAiResponseTransformerModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    AiResponseTransformerModule {
    override val type: ModuleType get() = ModuleType.AI_RESPONSE_TRANSFORMER
    private val json = KarandashJson.Default

    override val transformerPriority: Int get() = transport.getIntProperty(PROP_TRANSFORMER_PRIORITY, 100)

    override suspend fun transform(
        response: AiCompletionResponse,
        request: AiCompletionRequest,
        context: AiResponseTransformerContext,
    ): AiCompletionResponse {
        if (!transport.hasCapability(METHOD_TRANSFORM)) return response
        return transport.guarded(METHOD_TRANSFORM, response) {
            val responseJson = json.encodeToString(AiCompletionResponse.serializer(), response)
            val requestJson = json.encodeToString(AiCompletionRequest.serializer(), request)
            val contextJson = buildJsonObject {
                put("ruleId", context.ruleId)
                put("providerId", context.providerId)
            }.toString()
            val result = transport.call(METHOD_TRANSFORM, responseJson, requestJson, contextJson)
                ?: return response
            BridgeDeserializer.parseAiCompletionResponse(result)
        }
    }

    private companion object {
        const val PROP_TRANSFORMER_PRIORITY = "transformerPriority"
        const val METHOD_TRANSFORM = "aiTransformResponse"
    }
}
