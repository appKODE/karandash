package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiResponseTransformerContext
import io.karandash.api.ai.AiResponseTransformerModule

/**
 * Standalone example Karandash AI response transformer plugin.
 *
 * Demonstrates implementing [AiResponseTransformerModule] to post-process AI
 * completion responses. This example strips markdown code fences from responses,
 * which is useful when the AI wraps its output in triple-backtick blocks.
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleAiResponseTransformerPlugin : AiResponseTransformerModule {
    override val id: String = "example-jvm-ai-response-transformer"
    override val name: String = "Example JVM AI Response Transformer Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.AI_RESPONSE_TRANSFORMER

    override val transformerPriority: Int = 100

    override suspend fun initialize(context: PluginContext): Boolean = true

    /**
     * Strips leading and trailing markdown code fences from the response content.
     *
     * For example, transforms:
     * ```
     * ```json
     * {"key": "value"}
     * ```
     * ```
     * into: `{"key": "value"}`
     */
    override suspend fun transform(
        response: AiCompletionResponse,
        request: AiCompletionRequest,
        context: AiResponseTransformerContext,
    ): AiCompletionResponse {
        val cleaned = response.content
            .trimIndent()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return response.copy(content = cleaned)
    }
}
