package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiProviderException
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger

/**
 * Wraps an [AiProvider] with model fallback chain support.
 *
 * The caller-supplied (primary) model is always tried first. When it fails with a retryable
 * [AiProviderException] or [AiProviderException.ModelNotAvailable] (another model may exist), the
 * models of [modelChain] are tried in order (the primary is not repeated) before giving up. Any
 * other non-retryable error (a bad key, a refused request) fails every model alike and stops the chain.
 *
 * @param delegate The underlying AI provider to call
 * @param modelChain Ordered fallback model IDs tried after the primary model
 * @param onFallback Callback invoked when falling back from a failed model
 * @param logger Decision log: each fallback at info, the chain outcome at debug
 */
class FallbackAiProvider(
    private val delegate: AiProvider,
    private val modelChain: List<String>,
    private val logger: Logger = NoOpLogger,
    private val onFallback: (String, String) -> Unit,
) : AiProvider {
    override val providerId: String = delegate.providerId

    override suspend fun complete(
        prompt: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        temperature: Double,
        jsonMode: Boolean,
    ): AiResponse = completeRequest(AiCompletionRequest(prompt, systemPrompt, model, maxTokens, temperature, jsonMode))

    override suspend fun completeRequest(request: AiCompletionRequest): AiResponse {
        val model = request.model
        val effectiveChain = listOf(model) + modelChain.filter { it != model }
        var lastException: AiProviderException? = null

        for ((index, candidate) in effectiveChain.withIndex()) {
            try {
                val response = delegate.completeRequest(request.copy(model = candidate))
                if (index > 0) logger.info("[ai] fallback: model $candidate answered after $index failed model(s)")
                return response
            } catch (e: AiProviderException) {
                lastException = e
                if (!e.retryable && e !is AiProviderException.ModelNotAvailable) {
                    logger.debug("AI fallback: model $candidate failed non-retryable (${e.message}), chain stopped")
                    throw e
                }
                if (index < effectiveChain.lastIndex) {
                    logger.info("[ai] fallback: model $candidate failed (${e.message}), trying ${effectiveChain[index + 1]}")
                    onFallback(candidate, e.message ?: "unknown error")
                } else {
                    logger.warn("[ai] fallback: last model $candidate failed (${e.message}), chain exhausted")
                }
            }
        }

        throw lastException ?: AiProviderException("All models in fallback chain failed")
    }

    override suspend fun close() = delegate.close()
}
