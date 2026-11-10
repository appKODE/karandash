package io.karandash.cli.commands.explain

import io.karandash.api.config.ConfigPropertyType

/**
 * Renders an [ExplainResult] as Markdown documentation.
 *
 * Produces a structured document with tables per module showing field status,
 * suitable for saving to a file or piping into documentation tooling.
 */
object MarkdownExplainRenderer {
    /**
     * Render the full explain result as a Markdown string.
     */
    fun render(result: ExplainResult): String {
        val sb = StringBuilder()

        sb.appendLine("# Karandash Configuration Report")
        sb.appendLine()

        if (result.configPath != null) {
            sb.appendLine("**Config file:** `${result.configPath}`")
        } else {
            sb.appendLine("**Config file:** _(zero-config defaults)_")
        }
        sb.appendLine()

        if (result.globalIssues.isNotEmpty()) {
            sb.appendLine("## Global Issues")
            sb.appendLine()
            for (issue in result.globalIssues) {
                sb.appendLine("- $issue")
            }
            sb.appendLine()
        }

        if (result.configuredModules.isNotEmpty()) {
            sb.appendLine("## Configured Modules")
            sb.appendLine()
            for (report in result.configuredModules) {
                renderModuleReport(sb, report)
            }
        }

        val defaultEnabled = result.availableModules.filter { it.isDefaultEnabled }
        if (defaultEnabled.isNotEmpty()) {
            sb.appendLine("## Zero-Config Defaults")
            sb.appendLine()
            for (report in defaultEnabled) {
                renderModuleReport(sb, report)
            }
        }

        val unconfigured = result.availableModules.filter { !it.isDefaultEnabled }
        if (unconfigured.isNotEmpty()) {
            sb.appendLine("## Available (Not Configured)")
            sb.appendLine()
            val grouped = unconfigured.groupBy { it.moduleType }
            for ((type, modules) in grouped) {
                sb.appendLine("**${type}s:** ${modules.joinToString(", ") { "`${it.moduleId}`" }}")
                sb.appendLine()
            }
        }

        val issueCount = result.configuredModules.count { !it.isHealthy }
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine(
            "**Summary:** ${result.configuredModules.size} module(s) configured, " +
                "$issueCount with issues"
        )

        return sb.toString()
    }

    private fun renderModuleReport(
        sb: StringBuilder,
        report: ModuleReport
    ) {
        val statusBadge = if (report.isHealthy) "OK" else "ISSUES"
        sb.appendLine("### ${report.moduleType}: `${report.moduleId}` [$statusBadge]")
        sb.appendLine()
        if (report.description.isNotEmpty() && !report.description.startsWith("Unknown ")) {
            sb.appendLine("> ${report.description}")
            sb.appendLine()
        }

        if (report.description.startsWith("Unknown ")) {
            sb.appendLine("> Not found in module registry")
            sb.appendLine()
            return
        }

        if (report.fields.isNotEmpty()) {
            sb.appendLine("| Property | Value | Type | Status |")
            sb.appendLine("|----------|-------|------|--------|")
            for (field in report.fields) {
                val prop = field.property
                val value = when {
                    field.isSet -> "`${formatValue(field.actualValue)}`"
                    field.actualValue != null -> "`${formatValue(field.actualValue)}` _(default)_"
                    else -> "_(not set)_"
                }
                val status = when {
                    !field.isSet && !prop.required -> "-"
                    !field.isSet && prop.required -> "MISSING"
                    field.isValid -> "OK"
                    else -> "ERROR: ${field.issue}"
                }
                val typeStr = typeLabel(prop.type) + if (prop.required) " **required**" else ""
                sb.appendLine("| `${prop.name}` | $value | $typeStr | $status |")
            }
            sb.appendLine()
        }

        if (report.unknownKeys.isNotEmpty()) {
            sb.appendLine("**Unknown keys:**")
            sb.appendLine()
            for (unknown in report.unknownKeys) {
                val hint = if (unknown.suggestion != null) " (did you mean `${unknown.suggestion}`?)" else ""
                sb.appendLine("- `${unknown.key}`$hint")
            }
            sb.appendLine()
        }

        if (report.missingRequired.isNotEmpty()) {
            sb.appendLine("**Missing required fields:**")
            sb.appendLine()
            for (missing in report.missingRequired) {
                sb.appendLine("- `${missing.name}`: ${missing.description}")
            }
            sb.appendLine()
        }
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
