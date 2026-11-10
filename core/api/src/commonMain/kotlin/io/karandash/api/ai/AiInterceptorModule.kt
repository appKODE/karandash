package io.karandash.api.ai

import io.karandash.api.Module
import io.karandash.api.ModuleType
import kotlinx.serialization.Serializable

/**
 * Interceptor for AI completion requests (OkHttp interceptor pattern).
 *
 * Implementations can transform requests, route to alternative providers,
 * cache responses, rate-limit, log, or short-circuit the chain entirely.
 *
 * External tools (e.g. Qodo) ship a JAR/native plugin implementing this interface
 * to hook into every AI call with full context.
 *
 * Interceptors are sorted by [interceptorPriority] (ascending) and composed into
 * a chain. The lowest-priority interceptor is the outermost (called first).
 */
public interface AiInterceptorModule : Module {
    override val type: ModuleType get() = ModuleType.AI_INTERCEPTOR

    /**
     * Sort priority - lower values run first (outermost in chain).
     * Default is 100.
     */
    public val interceptorPriority: Int get() = 100

    /**
     * Intercept an AI completion request.
     *
     * Call [chain].proceed to continue to the next interceptor or terminal provider.
     * Return a response to short-circuit the remaining chain.
     *
     * @param request The (possibly transformed) completion request.
     * @param context Metadata about the call origin.
     * @param chain Continuation to the next interceptor or provider.
     * @return The completion response.
     */
    public suspend fun intercept(
        request: AiCompletionRequest,
        context: AiInterceptContext,
        chain: AiInterceptChain,
    ): AiCompletionResponse
}

/**
 * Metadata about the origin of an AI completion call.
 *
 * @param sourceModuleId The ID of the module that initiated the call, if known.
 * @param ruleId The rule ID being evaluated, if the call originates from a rule.
 * @param providerId The target provider ID, if explicitly specified.
 */
@Serializable
public data class AiInterceptContext(
    val sourceModuleId: String?,
    val ruleId: String?,
    val providerId: String?,
)

/**
 * Continuation handle for the interceptor chain.
 *
 * Calling [proceed] forwards the request to the next interceptor or,
 * if this is the last interceptor, to the terminal AI provider.
 */
public fun interface AiInterceptChain {
    public suspend fun proceed(request: AiCompletionRequest): AiCompletionResponse
}
