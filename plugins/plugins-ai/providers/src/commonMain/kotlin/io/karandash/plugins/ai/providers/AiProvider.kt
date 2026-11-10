package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.plugins.ai.core.AiDefaultModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * Provider interface used within the providers module for the legacy
 * `complete(prompt, systemPrompt, model, maxTokens, temperature)` API.
 *
 * External consumers should use [io.karandash.api.ai.AiProviderModule] instead.
 */
interface AiProvider {
    val providerId: String

    suspend fun complete(
        prompt: String,
        systemPrompt: String? = null,
        model: String = DEFAULT_MODEL,
        maxTokens: Int = 1000,
        temperature: Double = 0.3,
        jsonMode: Boolean = false,
    ): AiResponse

    /**
     * Completes a full SDK request. Unlike the positional [complete], this keeps
     * [AiCompletionRequest.responseSchema]; providers and wrappers that understand it override this.
     * The default degrades a schema to JSON mode, so the caller falls back to text parsing, and sends
     * [AiCompletionRequest.contextBlocks] inside the prompt.
     */
    suspend fun completeRequest(request: AiCompletionRequest): AiResponse =
        complete(
            prompt = request.withContextInPrompt().prompt,
            systemPrompt = request.systemPrompt,
            model = request.model,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            jsonMode = request.jsonMode || request.responseSchema != null,
        )

    /**
     * The completion as it is generated. The default is one chunk holding the [completeRequest] result; an HTTP
     * provider with `ai.stream: true` emits the provider's own deltas, usage on the last one.
     */
    fun streamCompletion(request: AiCompletionRequest): Flow<AiChunk> = flow { emit(completeRequest(request).toChunk()) }

    suspend fun close()

    companion object {
        const val DEFAULT_MODEL = AiDefaultModels.GENERIC
    }
}

/**
 * Response from an [AiProvider] completion call.
 *
 * Maps 1:1 to [AiCompletionResponse] with an additional [durationMs] field
 * for internal timing.
 */
@Serializable
data class AiResponse(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val model: String,
    val durationMs: Long = 0,
    /** Input tokens read from the provider's prompt cache (a subset of [promptTokens]). */
    val cacheReadTokens: Int = 0,
    /** Input tokens written to the provider's prompt cache (a subset of [promptTokens]). */
    val cacheWriteTokens: Int = 0,
    /** The reply stopped at the output token limit (`finish_reason=length`, `stop_reason=max_tokens`): never cached. */
    val truncated: Boolean = false,
)

/**
 * Bridge function that adapts the SDK [AiCompletionRequest] to the internal
 * [AiProvider.complete] signature and wraps the result as [AiCompletionResponse].
 */
suspend fun AiProvider.completeSdk(request: AiCompletionRequest): AiCompletionResponse {
    val response = completeRequest(request)
    return AiCompletionResponse(
        content = response.content,
        promptTokens = response.promptTokens,
        completionTokens = response.completionTokens,
        totalTokens = response.totalTokens,
        model = response.model,
        cacheReadTokens = response.cacheReadTokens,
        cacheWriteTokens = response.cacheWriteTokens,
    )
}
