package io.karandash.cli.commands.explain

import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType

/**
 * Renders an [ExplainResult] as a JSON string for machine consumption.
 *
 * Uses manual JSON construction (no kotlinx-serialization dependency required)
 * to keep the explain module self-contained.
 */
object JsonExplainRenderer {
    /**
     * Render the full explain result as a formatted JSON string.
     */
    fun render(result: ExplainResult): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"configPath\": ${jsonString(result.configPath)},")
        sb.appendLine("  \"healthy\": ${result.isHealthy},")

        if (result.globalIssues.isNotEmpty()) {
            sb.appendLine("  \"globalIssues\": [")
            result.globalIssues.forEachIndexed { idx, issue ->
                sb.append("    ${jsonString(issue)}")
                if (idx < result.globalIssues.size - 1) sb.append(",")
                sb.appendLine()
            }
            sb.appendLine("  ],")
        } else {
            sb.appendLine("  \"globalIssues\": [],")
        }

        sb.appendLine("  \"configuredModules\": [")
        renderModuleList(sb, result.configuredModules, "    ")
        sb.appendLine("  ],")

        sb.appendLine("  \"availableModules\": [")
        renderModuleList(sb, result.availableModules, "    ")
        sb.appendLine("  ]")

        sb.appendLine("}")
        return sb.toString()
    }

    private fun renderModuleList(
        sb: StringBuilder,
        modules: List<ModuleReport>,
        indent: String
    ) {
        val inner = "$indent    "
        val prop = "$indent      "
        modules.forEachIndexed { idx, report ->
            sb.appendLine("$indent{")
            sb.appendLine("$indent  \"moduleId\": ${jsonString(report.moduleId)},")
            sb.appendLine("$indent  \"moduleType\": ${jsonString(report.moduleType)},")
            sb.appendLine("$indent  \"description\": ${jsonString(report.description)},")
            sb.appendLine("$indent  \"configured\": ${report.isConfigured},")
            sb.appendLine("$indent  \"defaultEnabled\": ${report.isDefaultEnabled},")
            sb.appendLine("$indent  \"healthy\": ${report.isHealthy},")

            sb.appendLine("$indent  \"fields\": [")
            renderFields(sb, report.fields, inner, prop)
            sb.appendLine("$indent  ],")

            sb.appendLine("$indent  \"unknownKeys\": [")
            renderUnknownKeys(sb, report.unknownKeys, inner, prop)
            sb.appendLine("$indent  ],")

            sb.appendLine("$indent  \"missingRequired\": [")
            renderMissingRequired(sb, report.missingRequired, inner, prop)
            sb.appendLine("$indent  ]")

            sb.append("$indent}")
            if (idx < modules.size - 1) sb.append(",")
            sb.appendLine()
        }
    }

    private fun <T> renderJsonArray(
        sb: StringBuilder,
        items: List<T>,
        inner: String,
        prop: String,
        renderBody: (StringBuilder, T, String) -> Unit
    ) {
        items.forEachIndexed { idx, item ->
            sb.appendLine("$inner{")
            renderBody(sb, item, prop)
            sb.append("$inner}")
            if (idx < items.size - 1) sb.append(",")
            sb.appendLine()
        }
    }

    private fun renderFields(
        sb: StringBuilder,
        fields: List<FieldReport>,
        inner: String,
        prop: String
    ) {
        renderJsonArray(sb, fields, inner, prop) { out, field, p ->
            out.appendLine("${p}\"name\": ${jsonString(field.property.name)},")
            out.appendLine("${p}\"type\": ${jsonString(typeLabel(field.property.type))},")
            out.appendLine("${p}\"description\": ${jsonString(field.property.description)},")
            out.appendLine("${p}\"required\": ${field.property.required},")
            out.appendLine("${p}\"set\": ${field.isSet},")
            out.appendLine("${p}\"valid\": ${field.isValid},")
            out.appendLine("${p}\"value\": ${jsonValue(field.actualValue)},")
            out.appendLine("${p}\"defaultValue\": ${jsonValue(field.property.defaultValue)},")
            out.appendLine("${p}\"issue\": ${jsonString(field.issue)}")
        }
    }

    private fun renderUnknownKeys(
        sb: StringBuilder,
        unknownKeys: List<UnknownKeyReport>,
        inner: String,
        prop: String
    ) {
        renderJsonArray(sb, unknownKeys, inner, prop) { out, unknown, p ->
            out.appendLine("${p}\"key\": ${jsonString(unknown.key)},")
            out.appendLine("${p}\"suggestion\": ${jsonString(unknown.suggestion)}")
        }
    }

    private fun renderMissingRequired(
        sb: StringBuilder,
        missingRequired: List<ConfigProperty>,
        inner: String,
        prop: String
    ) {
        renderJsonArray(sb, missingRequired, inner, prop) { out, configProp, p ->
            out.appendLine("${p}\"name\": ${jsonString(configProp.name)},")
            out.appendLine("${p}\"description\": ${jsonString(configProp.description)}")
        }
    }

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
    }

    private fun jsonValue(value: Any?): String =
        when (value) {
            null -> {
                "null"
            }

            is String -> {
                jsonString(value)
            }

            is Boolean -> {
                value.toString()
            }

            is Number -> {
                value.toString()
            }

            is List<*> -> {
                "[${value.joinToString(", ") { jsonValue(it) }}]"
            }

            is Map<*, *> -> {
                "{${
                    value.entries.joinToString(", ") { (k, v) -> "${jsonString(k?.toString())}: ${jsonValue(v)}" }
                }}"
            }

            else -> {
                jsonString(value.toString())
            }
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
