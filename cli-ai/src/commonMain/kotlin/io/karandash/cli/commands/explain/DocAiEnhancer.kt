package io.karandash.cli.commands.explain

import io.karandash.api.support.rethrowCancellation
import io.karandash.plugins.ai.providers.AiProvider

/**
 * Enhances template-generated documentation with AI-generated examples and
 * natural language explanations.
 *
 * Uses a chunked per-rule strategy: each configured module gets a focused prompt,
 * and a final prompt generates a complete worked example. The AI-generated sections
 * are inserted into the template document.
 *
 * @param warn Receives one line per AI section that failed and was left out.
 */
class DocAiEnhancer(
    private val provider: AiProvider,
    private val warn: (String) -> Unit = {},
) {
    /**
     * Adds AI-generated examples and explanations to [templateDoc] for each configured module.
     */
    suspend fun enhance(
        templateDoc: String,
        result: ExplainResult
    ): String {
        val allConfigured = result.configuredModules +
            result.availableModules.filter { it.isDefaultEnabled }

        if (allConfigured.isEmpty()) return templateDoc

        val sb = StringBuilder(templateDoc)

        val ruleEnhancements = mutableListOf<String>()
        for (report in allConfigured) {
            val enhancement = enhanceRule(report)
            if (enhancement.isNotEmpty()) {
                ruleEnhancements.add(enhancement)
            }
        }

        if (ruleEnhancements.isNotEmpty()) {
            val insertPos = findInsertPosition(sb, "## Quick Reference Checklist")
            val enhancedSection = buildString {
                appendLine("## Examples and Common Mistakes")
                appendLine()
                for (enhancement in ruleEnhancements) {
                    appendLine(enhancement)
                }
            }
            sb.insert(insertPos, enhancedSection)
        }

        val workedExample = generateWorkedExample(allConfigured)
        if (workedExample.isNotEmpty()) {
            val checklistEnd = findInsertPosition(sb, "## Available Modules")
                .let { if (it == sb.length) findInsertPosition(sb, "---") else it }
            sb.insert(
                checklistEnd,
                buildString {
                    appendLine("## Worked Example")
                    appendLine()
                    appendLine(workedExample)
                    appendLine()
                }
            )
        }

        return sb.toString()
    }

    private suspend fun enhanceRule(report: ModuleReport): String {
        val schemaDescription = buildString {
            appendLine("Module: ${report.moduleId}")
            appendLine("Description: ${report.description}")
            appendLine("Configured fields:")
            for (field in report.fields) {
                val value = field.actualValue ?: field.property.defaultValue
                appendLine("  - ${field.property.name} (${field.property.type}): $value")
            }
        }

        val prompt = buildString {
            appendLine("Given this merge request validation rule configuration:")
            appendLine()
            appendLine(schemaDescription)
            appendLine()
            appendLine("Generate a concise section with:")
            appendLine("1. A correct example and an incorrect example in a markdown table (columns: Example | Valid | Why)")
            appendLine("2. One common mistake to avoid (one sentence)")
            appendLine()
            appendLine("Use markdown formatting. Be concise. No introductory text.")
        }

        return try {
            val response = provider.complete(
                prompt = prompt,
                systemPrompt = SYSTEM_PROMPT,
                maxTokens = MAX_TOKENS_PER_RULE,
                temperature = TEMPERATURE,
            )
            buildString {
                appendLine("### ${formatTitle(report.moduleId)}")
                appendLine()
                appendLine(response.content.trim())
                appendLine()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: LLM provider call; AI enhancement is optional
            e.rethrowCancellation()
            warn("Warning: AI section for ${report.moduleId} skipped: ${e.message}")
            ""
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun generateWorkedExample(modules: List<ModuleReport>): String {
        val rulesSummary = modules.joinToString("\n") { report ->
            val constraints = DocExplainRenderer.extractConstraints(report)
            "- ${report.moduleId}: ${constraints.joinToString("; ")}"
        }

        val prompt = buildString {
            appendLine("Given these active merge request validation rules:")
            appendLine()
            appendLine(rulesSummary)
            appendLine()
            appendLine("Generate ONE complete worked example of a merge request that passes all rules.")
            appendLine("Include: MR title, description, branch name, and commit messages.")
            appendLine("Format as a markdown code block. Be concise.")
        }

        return try {
            val response = provider.complete(
                prompt = prompt,
                systemPrompt = SYSTEM_PROMPT,
                maxTokens = MAX_TOKENS_WORKED_EXAMPLE,
                temperature = TEMPERATURE,
            )
            response.content.trim()
        } catch (e: Exception) {
            // boundary: AI provider complete() may throw any exception
            e.rethrowCancellation()
            warn("Warning: AI worked example skipped: ${e.message}")
            ""
        }
    }

    private fun findInsertPosition(
        sb: StringBuilder,
        heading: String
    ): Int {
        val index = sb.indexOf(heading)
        return if (index >= 0) index else sb.length
    }

    private fun formatTitle(moduleId: String): String =
        moduleId
            .split("-", "_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a technical writer documenting merge request validation rules. " +
                "Be precise and concise. Use markdown formatting."
        private const val TEMPERATURE = 0.2
        private const val MAX_TOKENS_PER_RULE = 1500
        private const val MAX_TOKENS_WORKED_EXAMPLE = 2000
    }
}
