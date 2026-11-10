package io.karandash.api.ai

/**
 * Platform-level AI service accessible to any module via [AiAwarePluginContext].
 *
 * Routes completion calls to registered [AiProviderModule] instances through
 * an optional [AiInterceptorModule] chain.
 */
public interface AiService {
    /** Whether at least one AI provider is registered and available. */
    public val isAvailable: Boolean

    /**
     * Send a completion request through the interceptor chain to a provider.
     *
     * @param request The completion request parameters.
     * @param providerId Target provider ID (e.g. "openai", "anthropic"). Null uses the default.
     * @return The completion response from the provider.
     * @throws AiProviderException on provider errors.
     */
    public suspend fun complete(
        request: AiCompletionRequest,
        providerId: String? = null,
    ): AiCompletionResponse
}
