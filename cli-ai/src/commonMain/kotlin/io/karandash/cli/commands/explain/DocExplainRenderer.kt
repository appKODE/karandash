package io.karandash.cli.commands.explain

import io.karandash.api.config.ConfigPropertyType
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.patterns.PatternDescriber
import io.karandash.patterns.PatternFix
import io.karandash.utils.suggestion.PatternExampleGenerator

/**
 * Renders an [ExplainResult] as user-friendly documentation with natural language
 * constraints, a config reference table, and a quick-reference checklist.
 *
 * Unlike [MarkdownExplainRenderer] (which shows field validity status for debugging),
 * this renderer produces documentation aimed at MR authors who need to understand
 * what the validation rules require.
 */
object DocExplainRenderer {
    /**
     * Render the explain result as a documentation-style Markdown string.
     */
    fun render(result: ExplainResult): String {
        val sb = StringBuilder()

        sb.appendLine("# Validation Rules Documentation")
        sb.appendLine()

        if (result.configPath != null) {
            sb.appendLine("Generated from configuration: `${result.configPath}`")
        } else {
            sb.appendLine("Generated from zero-config defaults.")
        }
        sb.appendLine()

        if (result.globalIssues.isNotEmpty()) {
            sb.appendLine("> **Warning:** ${result.globalIssues.size} configuration issue(s) detected.")
            sb.appendLine()
        }

        val allConfigured = result.configuredModules +
            result.availableModules.filter { it.isDefaultEnabled }

        if (allConfigured.isNotEmpty()) {
            sb.appendLine("## Active Rules")
            sb.appendLine()
            for (report in allConfigured) {
                renderRuleSection(sb, report)
            }
        }

        renderChecklist(sb, allConfigured)

        val unconfigured = result.availableModules.filter { !it.isDefaultEnabled }
        if (unconfigured.isNotEmpty()) {
            renderAvailableModules(sb, unconfigured)
        }

        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine(
            "*${allConfigured.size} active rule(s), " +
                "${result.availableModules.count { !it.isDefaultEnabled }} available but not configured.*"
        )

        return sb.toString()
    }

    private fun renderRuleSection(
        sb: StringBuilder,
        report: ModuleReport
    ) {
        sb.appendLine("### ${formatTitle(report.moduleId)}")
        sb.appendLine()

        if (report.description.isNotEmpty() && !report.description.startsWith("Unknown ")) {
            sb.appendLine(report.description)
            sb.appendLine()
        }

        if (report.fields.isNotEmpty()) {
            sb.appendLine("#### Configuration")
            sb.appendLine()
            sb.appendLine("| Property | Type | Value | Default | Required |")
            sb.appendLine("|----------|------|-------|---------|----------|")
            for (field in report.fields) {
                val prop = field.property
                val value = when {
                    field.isSet -> "`${formatValue(field.actualValue)}`"
                    else -> "-"
                }
                val default = if (prop.defaultValue != null) {
                    "`${formatValue(prop.defaultValue)}`"
                } else {
                    "-"
                }
                val required = if (prop.required) "Yes" else "No"
                sb.appendLine(
                    "| `${prop.name}` | ${typeLabel(prop.type)} | $value | $default | $required |"
                )
            }
            sb.appendLine()
        }

        val constraints = extractConstraints(report)
        if (constraints.isNotEmpty()) {
            sb.appendLine("#### Requirements")
            sb.appendLine()
            for (constraint in constraints) {
                sb.appendLine("- $constraint")
            }
            sb.appendLine()
        }
    }

    /**
     * `Must match regex:` with an example of a matching value when the pattern has a literal prefix to show
     * and what the regex means in plain English when it can be explained.
     */
    private fun patternConstraint(pattern: String): String {
        val example = (authorExample(pattern) ?: PatternExampleGenerator.generateExample(pattern))?.let { " (e.g. `$it`)" }.orEmpty()
        val meaning = PatternDescriber.explain(pattern)?.let { " — $it" }.orEmpty()
        return "Must match regex: `$pattern`$example$meaning"
    }

    /** The first `test { match ... }` example of a `pomsky:` pattern, read from the fix metadata rules attach to findings. */
    private fun authorExample(pattern: String): String? =
        pattern
            .takeIf { it.startsWith("pomsky:") }
            ?.let {
                PatternFix
                    .of(
                        it,
                        actual = "",
                        subject = "pattern",
                        diagnose = false
                    ).metadata()[WellKnownMetadataKeys.PATTERN_EXAMPLES]
            }?.lineSequence()
            ?.firstOrNull()

    internal fun extractConstraints(report: ModuleReport): List<String> {
        val constraints = mutableListOf<String>()

        for (field in report.fields) {
            val prop = field.property
            val effectiveValue = field.actualValue ?: prop.defaultValue

            if (prop.deprecated != null) {
                constraints.add("**${prop.name}** is deprecated: ${prop.deprecated}")
            }

            when {
                prop.name == "pattern" ||
                    prop.name.endsWith("Pattern") ||
                    prop.name.endsWith("-pattern") ||
                    prop.name.endsWith("_pattern") -> {
                    if (effectiveValue is String && effectiveValue.isNotEmpty()) {
                        constraints.add(patternConstraint(effectiveValue))
                    }
                }

                prop.name.startsWith("min") || prop.name.startsWith("minimum") -> {
                    if (effectiveValue != null) {
                        constraints.add(
                            "Minimum ${humanizeFieldName(prop.name.removePrefix("min").removePrefix("imum"))}: $effectiveValue"
                        )
                    }
                }

                prop.name.startsWith("max") || prop.name.startsWith("maximum") -> {
                    if (effectiveValue != null) {
                        constraints.add(
                            "Maximum ${humanizeFieldName(prop.name.removePrefix("max").removePrefix("imum"))}: $effectiveValue"
                        )
                    }
                }

                prop.type == ConfigPropertyType.ENUM -> {
                    val values = prop.enumValues
                    if (!values.isNullOrEmpty()) {
                        constraints.add("${prop.name} must be one of: ${values.joinToString(", ") { "`$it`" }}")
                    }
                }

                prop.type == ConfigPropertyType.STRING_LIST &&
                    (
                        prop.name.contains("allowed") ||
                            prop.name.contains("whitelist") ||
                            prop.name.contains("permit")
                    ) -> {
                    if (effectiveValue is List<*> && effectiveValue.isNotEmpty()) {
                        constraints.add(
                            "Allowed values for ${prop.name}: ${effectiveValue.joinToString(", ") { "`$it`" }}"
                        )
                    }
                }

                prop.type == ConfigPropertyType.BOOLEAN -> {
                    if (effectiveValue == true) {
                        constraints.add("${humanizeFieldName(prop.name)} is enabled")
                    }
                }
            }

            if (prop.required && !field.isSet) {
                constraints.add("**${prop.name}** is required but not configured")
            }
        }

        return constraints
    }

    private fun renderChecklist(
        sb: StringBuilder,
        modules: List<ModuleReport>
    ) {
        val checklistItems = mutableListOf<String>()

        for (report in modules) {
            val constraints = extractConstraints(report)
            if (constraints.isNotEmpty()) {
                val title = formatTitle(report.moduleId)
                for (constraint in constraints) {
                    if (!constraint.contains("is enabled") && !constraint.contains("deprecated")) {
                        checklistItems.add("$title: $constraint")
                    }
                }
            }
        }

        if (checklistItems.isNotEmpty()) {
            sb.appendLine("## Quick Reference Checklist")
            sb.appendLine()
            for (item in checklistItems) {
                sb.appendLine("- [ ] $item")
            }
            sb.appendLine()
        }
    }

    private fun renderAvailableModules(
        sb: StringBuilder,
        modules: List<ModuleReport>
    ) {
        sb.appendLine("## Available Modules (Not Configured)")
        sb.appendLine()
        val grouped = modules.groupBy { it.moduleType }
        for ((type, typeModules) in grouped) {
            sb.appendLine("**${type}s:**")
            sb.appendLine()
            for (module in typeModules) {
                val desc = if (module.description.isNotEmpty() && !module.description.startsWith("Unknown ")) {
                    " — ${module.description}"
                } else {
                    ""
                }
                sb.appendLine("- `${module.moduleId}`$desc")
            }
            sb.appendLine()
        }
    }

    private fun formatTitle(moduleId: String): String =
        moduleId
            .split("-", "_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }

    private fun humanizeFieldName(name: String): String {
        if (name.isEmpty()) return "value"
        val cleaned = name.removePrefix("-").removePrefix("_")
        if (cleaned.isEmpty()) return "value"
        return cleaned
            .replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2].lowercase()}" }
            .replace("-", " ")
            .replace("_", " ")
            .trim()
            .replaceFirstChar { it.lowercaseChar() }
    }

    private fun formatValue(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> value
            is List<*> -> "[${value.joinToString(", ")}]"
            is Map<*, *> -> "{...}"
            else -> value.toString()
        }

    private fun typeLabel(type: ConfigPropertyType): String =
        when (type) {
            ConfigPropertyType.STRING -> "string"
            ConfigPropertyType.INT -> "integer"
            ConfigPropertyType.BOOLEAN -> "boolean"
            ConfigPropertyType.STRING_LIST -> "string[]"
            ConfigPropertyType.ENUM -> "enum"
            ConfigPropertyType.OBJECT -> "object"
            ConfigPropertyType.OBJECT_LIST -> "object[]"
        }
}
