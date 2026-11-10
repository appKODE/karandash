package io.karandash.loader.bridge.ai

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.loader.BridgeDeserializer
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.support.KarandashJson

/**
 * Shared [AiProviderModule] implementation using [BridgeTransport].
 */
internal class BridgeAiProviderModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    AiProviderModule {
    override val type: ModuleType get() = ModuleType.AI_PROVIDER
    private val json = KarandashJson.Default

    override val providerId: String get() = transport.getProperty(PROP_PROVIDER_ID).ifEmpty { id }
    override val defaultModel: String get() = transport.getProperty(PROP_DEFAULT_MODEL)
    override val apiKeyEnvVar: String? get() = transport.getProperty(PROP_API_KEY_ENV_VAR).takeIf { it.isNotEmpty() }
    override val baseUrlEnvVar: String? get() = transport.getProperty(PROP_BASE_URL_ENV_VAR).takeIf { it.isNotEmpty() }

    override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
        return transport.guarded(METHOD_COMPLETE, AiCompletionResponse("", 0, 0, 0, "")) {
            val requestJson = json.encodeToString(AiCompletionRequest.serializer(), request)
            val result = transport.call(METHOD_COMPLETE, requestJson)
                ?: return AiCompletionResponse("", 0, 0, 0, "")
            BridgeDeserializer.parseAiCompletionResponse(result)
        }
    }

    private companion object {
        const val PROP_PROVIDER_ID = "providerId"
        const val PROP_DEFAULT_MODEL = "defaultModel"
        const val PROP_API_KEY_ENV_VAR = "apiKeyEnvVar"
        const val PROP_BASE_URL_ENV_VAR = "baseUrlEnvVar"
        const val METHOD_COMPLETE = "complete"
    }
}
