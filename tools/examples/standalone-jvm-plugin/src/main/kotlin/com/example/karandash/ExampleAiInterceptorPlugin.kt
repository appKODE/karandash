package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger

/**
 * Standalone example Karandash AI interceptor plugin.
 *
 * Demonstrates implementing [AiInterceptorModule] to observe or modify AI completion
 * calls without replacing the underlying provider. This example logs the request
 * and response for observability purposes (a passthrough interceptor).
 *
 * Real use-cases include: caching, rate limiting, routing to alternative providers,
 * injecting authentication headers, or auditing all AI calls.
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleAiInterceptorPlugin : AiInterceptorModule {
    override val id: String = "example-jvm-ai-interceptor"
    override val name: String = "Example JVM AI Interceptor Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.AI_INTERCEPTOR

    override val interceptorPriority: Int = 100

    // Sanctioned logger pattern: never store PluginContext itself (modules must stay stateless).
    private var _logger: Logger = NoOpLogger

    override suspend fun initialize(context: PluginContext): Boolean {
        _logger = context.logger
        return true
    }

    /**
     * Passthrough interceptor that logs the request and response.
     *
     * Calls [chain].proceed to forward the request unchanged to the next
     * interceptor or terminal provider.
     */
    override suspend fun intercept(
        request: AiCompletionRequest,
        context: AiInterceptContext,
        chain: AiInterceptChain,
    ): AiCompletionResponse {
        _logger.info(
            "ExampleAiInterceptor: intercepting request model=${request.model} " +
                "ruleId=${context.ruleId} provider=${context.providerId}"
        )
        val response = chain.proceed(request)
        _logger.info(
            "ExampleAiInterceptor: response totalTokens=${response.totalTokens} model=${response.model}"
        )
        return response
    }
}
