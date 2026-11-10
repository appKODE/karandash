package io.karandash.cli.commands.explain

import io.karandash.api.config.ConfigPropertyType

/**
 * Renders an [ExplainResult] as human-readable text for terminal output.
 *
 * Uses simple ASCII markers for status: check mark for valid, cross for issues,
 * dash for unset optional fields.
 */
object TextExplainRenderer {
    /**
     * Render the full explain result to a string.
     */
    fun render(result: ExplainResult): String {
        val sb = StringBuilder()

        sb.appendLine("Karandash Config Explanation")
        sb.appendLine("============================")
        sb.appendLine()

        if (result.configPath != null) {
            sb.appendLine("Config: ${result.configPath}")
        } else {
            sb.appendLine("Config: (zero-config defaults)")
        }
        sb.appendLine()

        if (result.globalIssues.isNotEmpty()) {
            sb.appendLine("[Global Issues]")
            for (issue in result.globalIssues) {
                sb.appendLine("  ! $issue")
            }
            sb.appendLine()
        }

        if (result.configuredModules.isNotEmpty()) {
            sb.appendLine("[Configured Modules]")
            sb.appendLine()
            for (report in result.configuredModules) {
                renderModuleReport(sb, report)
            }
        }

        val unconfigured = result.availableModules.filter { !it.isDefaultEnabled }
        val defaultEnabled = result.availableModules.filter { it.isDefaultEnabled }

        if (defaultEnabled.isNotEmpty()) {
            sb.appendLine("[Zero-Config Defaults (active)]")
            sb.appendLine()
            for (report in defaultEnabled) {
                renderModuleReport(sb, report)
            }
        }

        if (unconfigured.isNotEmpty()) {
            sb.appendLine("[Available (not configured)]")
            val grouped = unconfigured.groupBy { it.moduleType }
            for ((type, modules) in grouped) {
                sb.appendLine("  ${type}s: ${modules.joinToString(", ") { it.moduleId }}")
            }
            sb.appendLine()
        }

        val totalConfigured = result.configuredModules.size
        val issueCount = result.configuredModules.count { !it.isHealthy }
        sb.appendLine("---")
        if (issueCount == 0) {
            sb.appendLine("Result: $totalConfigured module(s) configured, no issues found")
        } else {
            sb.appendLine("Result: $totalConfigured module(s) configured, $issueCount with issues")
        }

        return sb.toString()
    }

    private fun renderModuleReport(
        sb: StringBuilder,
        report: ModuleReport
    ) {
        val status = if (report.isHealthy) "OK" else "ISSUES"
        val enabledTag = when {
            report.isConfigured -> "configured"
            report.isDefaultEnabled -> "zero-config default"
            else -> "not configured"
        }
        sb.appendLine("  == ${report.moduleType}: ${report.moduleId} ($enabledTag) [$status] ==")
        if (report.description.isNotEmpty() && report.description != "Unknown rule (not registered in the module registry)") {
            sb.appendLine("     ${report.description}")
        }

        if (report.description == "Unknown rule (not registered in the module registry)" ||
            report.description.startsWith("Unknown ")
        ) {
            sb.appendLine("     ! Not found in module registry")
            sb.appendLine()
            return
        }

        for (field in report.fields) {
            val prop = field.property
            val marker = when {
                !field.isSet -> "-"
                field.isValid -> "v"
                else -> "x"
            }
            val valueDisplay = when {
                field.isSet -> formatValue(field.actualValue)
                field.actualValue != null -> "${formatValue(field.actualValue)} (default)"
                else -> "<not set>"
            }
            val typeLabel = typeLabel(prop.type)
            sb.append("     [$marker] ${prop.name}: $valueDisplay  ($typeLabel")
            if (prop.required) sb.append(", required")
            if (prop.deprecated != null) sb.append(", DEPRECATED: ${prop.deprecated}")
            sb.appendLine(")")

            if (field.issue != null) {
                sb.appendLine("         ! ${field.issue}")
            }

            if (prop.enumValues != null && requireNotNull(prop.enumValues) { "enumValues unexpectedly null" }.isNotEmpty() && field.isSet) {
                sb.appendLine("         Allowed: ${requireNotNull(prop.enumValues) { "enumValues unexpectedly null" }.joinToString(", ")}")
            }
        }

        for (unknown in report.unknownKeys) {
            sb.append("     [!] Unknown key: \"${unknown.key}\"")
            if (unknown.suggestion != null) {
                sb.append(" -- did you mean \"${unknown.suggestion}\"?")
            }
            sb.appendLine()
        }

        for (missing in report.missingRequired) {
            sb.appendLine("     [!] Missing required field: \"${missing.name}\" (${missing.description})")
        }

        sb.appendLine()
    }

    private fun formatValue(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "\"$value\""
            is List<*> -> "[${value.joinToString(", ") { formatValue(it) }}]"
            is Map<*, *> -> "{${value.entries.joinToString(", ") { "${it.key}: ${formatValue(it.value)}" }}}"
            else -> value.toString()
        }

    private fun typeLabel(type: ConfigPropertyType): String =
        when (type) {
            ConfigPropertyType.STRING -> "string"
            ConfigPropertyType.INT -> "integer"
            ConfigPropertyType.BOOLEAN -> "boolean"
            ConfigPropertyType.STRING_LIST -> "string list"
            ConfigPropertyType.ENUM -> "enum"
            ConfigPropertyType.OBJECT -> "object"
            ConfigPropertyType.OBJECT_LIST -> "object list"
        }
}
