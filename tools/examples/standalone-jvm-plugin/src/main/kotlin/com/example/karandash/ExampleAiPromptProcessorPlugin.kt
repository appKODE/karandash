package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiPromptProcessorContext
import io.karandash.api.ai.AiPromptProcessorModule

/**
 * Standalone example Karandash AI prompt processor plugin.
 *
 * Demonstrates implementing [AiPromptProcessorModule] to transform prompts before
 * they are sent to the AI provider. This example appends organization-specific
 * guidelines to every prompt and injects a custom system instruction.
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleAiPromptProcessorPlugin : AiPromptProcessorModule {
    override val id: String = "example-jvm-ai-prompt-processor"
    override val name: String = "Example JVM AI Prompt Processor Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.AI_PROMPT_PROCESSOR

    override val processorPriority: Int = 100

    override suspend fun initialize(context: PluginContext): Boolean = true

    /**
     * Appends a team-specific instruction footer to the user prompt.
     */
    override suspend fun processPrompt(
        prompt: String,
        context: AiPromptProcessorContext,
    ): String {
        return buildString {
            append(prompt)
            appendLine()
            appendLine()
            append("Note: Apply Example Corp coding standards when evaluating this MR.")
        }
    }

    /**
     * Prepends a team-specific preamble to the system prompt.
     */
    override suspend fun processSystemPrompt(
        systemPrompt: String?,
        context: AiPromptProcessorContext,
    ): String? {
        val preamble = "You are a code reviewer following Example Corp engineering guidelines. "
        return if (systemPrompt != null) "$preamble$systemPrompt" else preamble
    }
}
