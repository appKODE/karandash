package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.models.MergeRequest

/**
 * Standalone example Karandash AI context enricher plugin.
 *
 * Demonstrates implementing [AiContextEnricherModule] to inject additional metadata
 * into AI prompts via template placeholders. This example adds basic MR statistics
 * as context (file count, total changes) which can be referenced in prompt templates
 * using `{{totalFiles}}` and `{{totalChanges}}`.
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleAiContextEnricherPlugin : AiContextEnricherModule {
    override val id: String = "example-jvm-ai-context-enricher"
    override val name: String = "Example JVM AI Context Enricher Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.AI_CONTEXT_ENRICHER

    override val enricherPriority: Int = 100

    override suspend fun initialize(context: PluginContext): Boolean = true

    /**
     * Injects MR statistics into the prompt context.
     *
     * The returned keys become available as `{{totalFiles}}` and `{{totalChanges}}`
     * in prompt templates.
     */
    override suspend fun enrich(
        mr: MergeRequest,
        context: AiContextEnricherContext,
    ): Map<String, String> {
        val totalFiles = mr.changes.size
        val totalAdditions = mr.changes.sumOf { it.additions }
        val totalDeletions = mr.changes.sumOf { it.deletions }
        return mapOf(
            "totalFiles" to totalFiles.toString(),
            "totalAdditions" to totalAdditions.toString(),
            "totalDeletions" to totalDeletions.toString(),
            "totalChanges" to (totalAdditions + totalDeletions).toString(),
            "commitCount" to mr.commits.size.toString(),
        )
    }
}
