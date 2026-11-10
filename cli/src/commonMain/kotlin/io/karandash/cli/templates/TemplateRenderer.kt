package io.karandash.cli.templates

import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import net.mamoe.yamlkt.Yaml

/**
 * Renders configuration objects as Kotlin DSL code, YAML, or JSON.
 * Converts nested map structures to idiomatic Kotlin builder syntax or standard formats.
 */
object TemplateRenderer {
    /**
     * Render a configuration object as Kotlin DSL.
     *
     * @param config Configuration object to render
     * @return Kotlin DSL code as string
     */
    fun renderToKotlinDsl(config: JsonObject): String {
        val builder = StringBuilder()
        renderMap(config, builder, 0)
        return builder.toString().trimEnd()
    }

    /**
     * Render a configuration object as YAML.
     *
     * @param config Configuration object to render
     * @return YAML string
     */
    fun renderToYaml(config: JsonObject): String = Yaml.Default.encodeToString(toPlain(config) as Map<*, *>)

    /**
     * Render a configuration object as JSON.
     *
     * @param config Configuration object to render
     * @return JSON string (pretty-printed)
     */
    fun renderToJson(config: JsonObject): String {
        val builder = StringBuilder()
        renderJsonValue(config, builder, 0)
        return builder.toString()
    }

    // parser-boundary: the YAML emitter encodes untyped maps, so the object is lowered right at its edge
    private fun toPlain(element: JsonElement): Any? =
        when (element) {
            is JsonNull -> null

            is JsonObject -> element.entries.associate { (k, v) -> k to toPlain(v) }

            is JsonArray -> element.map(::toPlain)

            is JsonPrimitive -> when {
                element.isString -> element.content
                else -> element.booleanOrNull ?: element.intOrNull ?: element.longOrNull ?: element.content
            }
        }

    private fun renderJsonValue(
        value: JsonElement,
        builder: StringBuilder,
        indent: Int
    ) {
        when (value) {
            is JsonObject -> {
                builder.append("{\n")
                val entries = value.entries.toList()
                entries.forEachIndexed { index, (key, v) ->
                    val indentStr = "  ".repeat(indent + 1)
                    builder.append("$indentStr\"${escapeString(key)}\": ")
                    renderJsonValue(v, builder, indent + 1)
                    if (index < entries.size - 1) {
                        builder.append(",")
                    }
                    builder.append("\n")
                }
                val indentStr = "  ".repeat(indent)
                builder.append("$indentStr}")
            }

            is JsonArray -> {
                builder.append("[\n")
                value.forEachIndexed { index, item ->
                    val indentStr = "  ".repeat(indent + 1)
                    builder.append(indentStr)
                    renderJsonValue(item, builder, indent + 1)
                    if (index < value.size - 1) {
                        builder.append(",")
                    }
                    builder.append("\n")
                }
                val indentStr = "  ".repeat(indent)
                builder.append("$indentStr]")
            }

            is JsonNull -> {
                builder.append("null")
            }

            is JsonPrimitive -> {
                builder.append(renderValue(value))
            }
        }
    }

    private fun renderMap(
        map: JsonObject,
        builder: StringBuilder,
        indent: Int
    ) {
        val entries = map.entries.toList()
        entries.forEachIndexed { index, (key, value) ->
            renderEntry(key, value, builder, indent)
            // Add blank line between top-level blocks (indent == 0)
            if (indent == 0 && index < entries.size - 1) {
                builder.append("\n")
            }
        }
    }

    private fun renderEntry(
        key: String,
        value: JsonElement,
        builder: StringBuilder,
        indent: Int
    ) {
        val indentStr = "    ".repeat(indent)
        val kotlinKey = kebabToCamelCase(key)

        when (value) {
            is JsonObject -> {
                builder.append("$indentStr$kotlinKey {\n")
                renderMap(value, builder, indent + 1)
                builder.append("$indentStr}\n")
            }

            is JsonArray -> {
                renderList(kotlinKey, value, builder, indent)
            }

            else -> {
                builder.append("$indentStr$kotlinKey = ${renderValue(value)}\n")
            }
        }
    }

    private fun kebabToCamelCase(key: String): String = ConfigKeyNormalizer.kebabToCamelCase(key)

    private fun renderList(
        key: String,
        list: JsonArray,
        builder: StringBuilder,
        indent: Int
    ) {
        val indentStr = "    ".repeat(indent)

        if (list.isEmpty()) {
            builder.append("${indentStr}$key = listOf()\n")
        } else if (list.all { it is JsonObject }) {
            // All elements are objects: render multiple blocks with the same name
            list.forEach { item ->
                builder.append("$indentStr$key {\n")
                renderMap(item as JsonObject, builder, indent + 1)
                builder.append("$indentStr}\n")
            }
        } else {
            val elements = list.joinToString(", ") { renderValue(it) }
            builder.append("${indentStr}$key = listOf($elements)\n")
        }
    }

    private fun renderValue(value: JsonElement): String =
        when {
            value is JsonNull -> "null"
            value is JsonPrimitive && value.isString -> "\"${escapeString(value.content)}\""
            value is JsonPrimitive -> value.content
            else -> "\"$value\""
        }

    private fun escapeString(str: String): String =
        str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
