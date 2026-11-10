package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger

/**
 * Standalone example Karandash AI provider plugin.
 *
 * Demonstrates implementing [AiProviderModule] to integrate a custom or
 * third-party LLM. This stub returns a hardcoded response for illustration
 * purposes; a real implementation would make HTTP calls to the AI provider API.
 *
 * Usage in config:
 * ```yaml
 * ai:
 *   provider: example-stub
 *   model: example-model-1
 * ```
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleAiProviderPlugin : AiProviderModule {
    override val id: String = "example-jvm-ai-provider"
    override val name: String = "Example JVM AI Provider Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.AI_PROVIDER

    override val providerId: String = "example-stub"

    // Sanctioned logger pattern: never store PluginContext itself (modules must stay stateless).
    private var _logger: Logger = NoOpLogger

    override suspend fun initialize(context: PluginContext): Boolean {
        _logger = context.logger
        return true
    }

    /**
     * Returns a stub completion response without making any real API calls.
     *
     * A real implementation would send [request] to an external LLM API endpoint
     * and parse the JSON response into [AiCompletionResponse].
     */
    override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
        _logger.info("ExampleAiProvider: completing request for model=${request.model}")
        val stubContent = "This is a stub AI response for prompt: ${request.prompt.take(80)}..."
        return AiCompletionResponse(
            content = stubContent,
            promptTokens = request.prompt.length / 4,
            completionTokens = stubContent.length / 4,
            totalTokens = (request.prompt.length + stubContent.length) / 4,
            model = request.model,
        )
    }
}
