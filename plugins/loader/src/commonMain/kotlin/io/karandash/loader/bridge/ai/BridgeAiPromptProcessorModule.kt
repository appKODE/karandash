package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiPromptProcessorContext
import io.karandash.api.ai.AiPromptProcessorModule
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared [AiPromptProcessorModule] implementation using [BridgeTransport].
 */
internal class BridgeAiPromptProcessorModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    AiPromptProcessorModule {
    override val type: ModuleType get() = ModuleType.AI_PROMPT_PROCESSOR
    override val processorPriority: Int get() = transport.getIntProperty(PROP_PROCESSOR_PRIORITY, 100)

    override suspend fun processPrompt(
        prompt: String,
        context: AiPromptProcessorContext,
    ): String {
        if (!transport.hasCapability(METHOD_PROCESS_PROMPT)) return prompt
        return transport.guarded(METHOD_PROCESS_PROMPT, prompt) {
            val contextJson = serializeContext(context)
            transport.call(METHOD_PROCESS_PROMPT, prompt, contextJson) ?: prompt
        }
    }

    override suspend fun processSystemPrompt(
        systemPrompt: String?,
        context: AiPromptProcessorContext,
    ): String? {
        if (!transport.hasCapability(METHOD_PROCESS_SYSTEM_PROMPT)) return systemPrompt
        return transport.guarded(METHOD_PROCESS_SYSTEM_PROMPT, systemPrompt) {
            val contextJson = serializeContext(context)
            val promptArg = systemPrompt ?: BridgeConstants.NULL_SENTINEL
            val result = transport.call(METHOD_PROCESS_SYSTEM_PROMPT, promptArg, contextJson) ?: return systemPrompt
            result.takeIf { it != BridgeConstants.NULL_SENTINEL }
        }
    }

    private fun serializeContext(context: AiPromptProcessorContext): String =
        buildJsonObject {
            put("ruleId", context.ruleId)
            put("model", context.model)
            put("providerId", context.providerId)
        }.toString()

    private companion object {
        const val PROP_PROCESSOR_PRIORITY = "processorPriority"
        const val METHOD_PROCESS_PROMPT = "aiProcessPrompt"
        const val METHOD_PROCESS_SYSTEM_PROMPT = "aiProcessSystemPrompt"
    }
}
