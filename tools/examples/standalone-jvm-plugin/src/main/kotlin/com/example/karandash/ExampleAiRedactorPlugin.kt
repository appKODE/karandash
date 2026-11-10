package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiRedactorContext
import io.karandash.api.ai.AiRedactorModule

/**
 * Standalone example Karandash AI redactor plugin.
 *
 * Demonstrates implementing [AiRedactorModule] to strip sensitive content from
 * prompts before they are sent to an AI provider. This example redacts patterns
 * specific to an organization (internal hostnames, employee IDs, internal tokens).
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleAiRedactorPlugin : AiRedactorModule {
    override val id: String = "example-jvm-ai-redactor"
    override val name: String = "Example JVM AI Redactor Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.AI_REDACTOR

    override val redactorPriority: Int = 100

    override suspend fun initialize(context: PluginContext): Boolean = true

    /**
     * Replaces organization-specific sensitive patterns with redaction placeholders.
     *
     * Patterns redacted:
     * - Internal hostnames matching `*.internal.example.com`
     * - Employee IDs matching `EMP-\d{6}`
     * - Internal API tokens matching `EXAMPLE_TOKEN_[A-Za-z0-9]+`
     */
    override suspend fun redact(
        text: String,
        context: AiRedactorContext,
    ): String {
        return text
            .replace(Regex("""[\w.-]+\.internal\.example\.com"""), "[INTERNAL-HOST]")
            .replace(Regex("""EMP-\d{6}"""), "[EMPLOYEE-ID]")
            .replace(Regex("""EXAMPLE_TOKEN_[A-Za-z0-9]+"""), "[REDACTED-TOKEN]")
    }
}
