package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiRedactorContext
import io.karandash.api.ai.AiRedactorModule
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared [AiRedactorModule] implementation using [BridgeTransport].
 */
internal class BridgeAiRedactorModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    AiRedactorModule {
    override val type: ModuleType get() = ModuleType.AI_REDACTOR
    override val redactorPriority: Int get() = transport.getIntProperty(PROP_REDACTOR_PRIORITY, 100)

    override suspend fun redact(
        text: String,
        context: AiRedactorContext,
    ): String {
        if (!transport.hasCapability(METHOD_REDACT)) return text
        return transport.guarded(METHOD_REDACT, text) {
            val contextJson = buildJsonObject {
                put("ruleId", context.ruleId)
                put("providerId", context.providerId)
            }.toString()
            transport.call(METHOD_REDACT, text, contextJson) ?: text
        }
    }

    private companion object {
        const val PROP_REDACTOR_PRIORITY = "redactorPriority"
        const val METHOD_REDACT = "aiRedact"
    }
}
