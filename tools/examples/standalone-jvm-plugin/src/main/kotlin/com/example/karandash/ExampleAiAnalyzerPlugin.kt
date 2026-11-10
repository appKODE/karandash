package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity

/**
 * Standalone example Karandash AI analyzer plugin.
 *
 * Demonstrates implementing [AiAnalyzerModule] to add a new AI-powered validation
 * rule. This example checks whether the MR description is sufficiently detailed
 * by asking an LLM to evaluate it.
 *
 * Usage in config:
 * ```yaml
 * rules:
 *   ai-example-description-check:
 *     enabled: true
 * ```
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleAiAnalyzerPlugin : AiAnalyzerModule {
    override val id: String = "example-jvm-ai-analyzer"
    override val name: String = "Example JVM AI Analyzer Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.AI_ANALYZER

    override val analyzerId: String = "ai-example-description-check"
    override val defaultPrompt: String = "Evaluate whether this MR description is sufficiently detailed. " +
        "Reply with PASS if the description clearly explains what changed and why, " +
        "or FAIL:<reason> if it is missing important context."
    override val defaultEnabled: Boolean = false

    /** The host's budget gate checks this before [analyze] runs and records a skip when it does not fit. */
    override val estimatedTokens: Int = 500

    override suspend fun initialize(context: PluginContext): Boolean = true

    /**
     * Asks the AI service to evaluate the MR description quality.
     *
     * Returns an empty list if the AI judges the description as sufficient,
     * or a single [Finding] with the AI's feedback if it is lacking.
     */
    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        if (!context.aiService.isAvailable) return emptyList()

        val prompt = buildString {
            appendLine("MR Title: ${context.mr.title}")
            appendLine("MR Description: ${context.mr.description ?: "(empty)"}")
        }

        val response = context.aiService.complete(
            AiCompletionRequest(
                prompt = prompt,
                systemPrompt = defaultPrompt,
                model = context.model,
                maxTokens = estimatedTokens,
            )
        )
        context.budget.recordSpend(response.totalTokens)

        val content = response.content.trim()
        return if (content.startsWith("FAIL")) {
            val reason = content.removePrefix("FAIL:").trim().ifEmpty { "Description is insufficient." }
            listOf(
                Finding(
                    rule = analyzerId,
                    message = "AI description check failed: $reason",
                    severity = Severity.WARNING,
                    location = null,
                )
            )
        } else {
            emptyList()
        }
    }
}
