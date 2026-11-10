package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.loader.BridgeDeserializer
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared [AiInterceptorModule] implementation using [BridgeTransport].
 *
 * Serializes request and context to JSON, delegates to the plugin transport,
 * and falls back to [AiInterceptChain.proceed] when the plugin returns null. While the guest runs, its
 * `ai.proceed` host call (see [io.karandash.loader.bridge.host.HostServiceDispatcher]) continues the chain.
 */
internal class BridgeAiInterceptorModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    AiInterceptorModule {
    override val type: ModuleType get() = ModuleType.AI_INTERCEPTOR
    private val json = KarandashJson.Default

    override val interceptorPriority: Int get() = transport.getIntProperty(PROP_INTERCEPTOR_PRIORITY, 100)

    override suspend fun intercept(
        request: AiCompletionRequest,
        context: AiInterceptContext,
        chain: AiInterceptChain,
    ): AiCompletionResponse {
        if (!transport.hasCapability(METHOD_INTERCEPT)) return chain.proceed(request)
        return transport.guarded(METHOD_INTERCEPT, chain.proceed(request)) {
            val requestJson = json.encodeToString(AiCompletionRequest.serializer(), request)
            val contextJson = buildJsonObject {
                put("sourceModuleId", context.sourceModuleId)
                put("ruleId", context.ruleId)
                put("providerId", context.providerId)
            }.toString()
            val call = suspend { transport.call(METHOD_INTERCEPT, requestJson, contextJson) }
            // the guest's `ai.proceed` host call runs the rest of this chain
            val host = transport.host
            val result = (if (host != null) host.withInterceptChain(chain, call) else call()) ?: return chain.proceed(request)
            BridgeDeserializer.parseAiCompletionResponse(result)
        }
    }

    private companion object {
        const val PROP_INTERCEPTOR_PRIORITY = "interceptorPriority"
        const val METHOD_INTERCEPT = "aiIntercept"
    }
}
