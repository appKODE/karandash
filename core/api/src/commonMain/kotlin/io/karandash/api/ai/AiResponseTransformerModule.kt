package io.karandash.api.ai

import io.karandash.api.Module
import io.karandash.api.ModuleType
import kotlinx.serialization.Serializable

/**
 * Post-processes AI completion responses after the interceptor chain returns.
 *
 * Transformers run in [transformerPriority] order (ascending, lowest first)
 * and can modify, filter, or enrich response content before it reaches the caller.
 *
 * Example use-cases: stripping markdown fences, normalizing JSON output,
 * or injecting compliance disclaimers.
 */
public interface AiResponseTransformerModule : Module {
    override val type: ModuleType get() = ModuleType.AI_RESPONSE_TRANSFORMER

    /** Sort priority - lower values run first. Default is 100. */
    public val transformerPriority: Int get() = 100

    /**
     * Transform a completion response.
     *
     * @param response The response returned from the interceptor/provider chain.
     * @param request The original (post-processing) request that produced this response.
     * @param context Metadata about the completion call origin.
     * @return Transformed response.
     */
    public suspend fun transform(
        response: AiCompletionResponse,
        request: AiCompletionRequest,
        context: AiResponseTransformerContext,
    ): AiCompletionResponse
}

/**
 * Context passed to response transformers.
 *
 * @param ruleId The rule ID being evaluated, if known.
 * @param providerId The provider ID that served the request.
 */
@Serializable
public data class AiResponseTransformerContext(
    val ruleId: String?,
    val providerId: String?,
)
