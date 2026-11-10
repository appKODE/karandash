package io.karandash.loader.bridge.host

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAwarePluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiProviderException
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `ai.complete` and `ai.proceed`, one schema on every transport.
 *
 * `ai.complete` request: `{prompt, systemPrompt?, model?, maxTokens?, temperature?, jsonMode?}`; response:
 * `{content, model, usage: {promptTokens, completionTokens, totalTokens}}` or `{err, retryable?}`. Inside `analyze`
 * it runs on that call's [AiAnalyzerContext] (its AI service, model default and context blocks), checks its budget
 * first and records the spend after; elsewhere it uses the host context's AI service without a budget.
 *
 * `ai.proceed` (inside `intercept` only) takes a whole [AiCompletionRequest] and answers like `ai.complete`.
 */
internal object HostAiServices {
    private const val DEFAULT_MAX_TOKENS = 2000
    private const val DEFAULT_TEMPERATURE = 0.2
    private val json = KarandashJson.Default

    suspend fun complete(
        context: PluginContext,
        analyzer: AiAnalyzerContext?,
        request: JsonObject,
    ): JsonElement {
        val ai = analyzer?.aiService ?: (context as? AiAwarePluginContext)?.ai
        if (ai == null || !ai.isAvailable) return HostReply.errJson("ai unavailable")
        val wire = json.decodeFromJsonElement(HostAiRequest.serializer(), request)
        val budget = analyzer?.budget
        if (budget != null && !budget.canSpend(wire.maxTokens)) return HostReply.errJson("budget exhausted")
        val completion =
            AiCompletionRequest(
                prompt = wire.prompt,
                systemPrompt = wire.systemPrompt,
                model = wire.model ?: analyzer?.model.orEmpty(),
                maxTokens = wire.maxTokens,
                temperature = wire.temperature,
                jsonMode = wire.jsonMode,
                contextBlocks = analyzer?.contextBlocks?.map { it.content }.orEmpty(),
            )
        return answer {
            ai.complete(completion).also { budget?.recordSpend(it.totalTokens) }
        }
    }

    suspend fun proceed(
        chain: AiInterceptChain?,
        request: JsonObject,
    ): JsonElement {
        chain ?: return HostReply.errJson("ai.proceed outside intercept")
        val completion = json.decodeFromJsonElement(AiCompletionRequest.serializer(), request)
        return answer { chain.proceed(completion) }
    }

    private suspend fun answer(block: suspend () -> AiCompletionResponse): JsonElement {
        val response =
            try {
                block()
            } catch (e: AiProviderException) {
                return buildJsonObject {
                    put("err", e.message ?: "ai provider failed")
                    put("retryable", e.retryable)
                }
            }
        return buildJsonObject {
            put("content", response.content)
            put("model", response.model)
            putJsonObject("usage") {
                put("promptTokens", response.promptTokens)
                put("completionTokens", response.completionTokens)
                put("totalTokens", response.totalTokens)
            }
        }
    }

    /** The pinned `ai.complete` request. */
    @Serializable
    data class HostAiRequest(
        val prompt: String = "",
        val systemPrompt: String? = null,
        val model: String? = null,
        val maxTokens: Int = DEFAULT_MAX_TOKENS,
        val temperature: Double = DEFAULT_TEMPERATURE,
        val jsonMode: Boolean = false,
    )
}
