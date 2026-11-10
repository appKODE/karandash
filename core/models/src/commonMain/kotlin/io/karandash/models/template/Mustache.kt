package io.karandash.models.template

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A logic-less Mustache subset over a [JsonElement] context: no environment, file or network access.
 *
 * Supported: `{{name}}` (escaped with the caller's escaper), `{{{name}}}` and `{{&name}}` (raw), dotted names and
 * `{{.}}`, sections over lists, objects and truthy values, inverted sections, `{{>partial}}` (with standalone
 * indentation), `{{! comments }}` and standalone-line stripping as in the Mustache spec. A section named after one of
 * the [TemplateEscapes.LAMBDAS] that is not a data key renders its body and escapes the result
 * (`{{#md}}{{{text}}}{{/md}}`). Set-delimiter tags are rejected.
 *
 * Falsy values: missing, `null`, `false`, `0`, an empty list and an empty string (so `{{#more}}` works on a count). A missing partial renders as nothing.
 * Parse errors throw [IllegalArgumentException]; output over `maxOutputChars` throws [IllegalStateException].
 */
public object Mustache {
    /** The default output cap, in characters. */
    public const val DEFAULT_MAX_OUTPUT_CHARS: Int = 1_000_000

    private const val MAX_PARTIAL_DEPTH = 16

    /** Renders [template] against [data]. */
    public fun render(
        template: String,
        data: JsonElement,
        partials: Map<String, String> = emptyMap(),
        escape: (String) -> String = TemplateEscapes::html,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    ): String {
        val out = StringBuilder()
        Renderer(partials, escape, maxOutputChars, out).render(MustacheParser(template).parse(), listOf(data), 0)
        return out.toString()
    }

    /** Parses [template] without rendering it; throws [IllegalArgumentException] when it is malformed. */
    public fun validate(template: String) {
        MustacheParser(template).parse()
    }

    /** @lifecycle per-run -- one per [render] call; [parsed] caches each (partial, indent) parse for that call. */
    private class Renderer(
        val partials: Map<String, String>,
        val escape: (String) -> String,
        val max: Int,
        val out: StringBuilder,
        val parsed: MutableMap<Pair<String, String>, List<MustacheNode>> = mutableMapOf(),
    ) {
        fun render(
            nodes: List<MustacheNode>,
            stack: List<JsonElement>,
            depth: Int,
        ) {
            for (node in nodes) {
                when (node) {
                    is MustacheNode.Text -> append(node.text)
                    is MustacheNode.Variable -> lookup(stack, node.name)?.let { append(if (node.escaped) escape(text(it)) else text(it)) }
                    is MustacheNode.Section -> section(node, stack, depth)
                    is MustacheNode.Partial -> partial(node, stack, depth)
                }
            }
        }

        private fun section(
            node: MustacheNode.Section,
            stack: List<JsonElement>,
            depth: Int,
        ) {
            val value = lookup(stack, node.name)
            val lambda = TemplateEscapes.LAMBDAS[node.name]
            when {
                node.inverted -> {
                    if (!truthy(value)) render(node.children, stack, depth)
                }

                value == null && lambda != null -> {
                    val inner = Renderer(partials, escape, max, StringBuilder(), parsed)
                    inner.render(node.children, stack, depth)
                    append(lambda(inner.out.toString()))
                }

                !truthy(value) -> {
                    Unit
                }

                value is JsonArray -> {
                    value.forEach { render(node.children, stack + it, depth) }
                }

                else -> {
                    render(node.children, stack + checkNotNull(value) { "truthy value is never null" }, depth)
                }
            }
        }

        private fun partial(
            node: MustacheNode.Partial,
            stack: List<JsonElement>,
            depth: Int,
        ) {
            require(depth < MAX_PARTIAL_DEPTH) { "Partials nest deeper than $MAX_PARTIAL_DEPTH levels (recursive '${node.name}'?)" }
            val source = partials[node.name] ?: return
            val nodes = parsed.getOrPut(node.name to node.indent) { MustacheParser(indent(source, node.indent)).parse() }
            render(nodes, stack, depth + 1)
        }

        private fun append(text: String) {
            out.append(text)
            check(out.length <= max) { "Template output exceeds $max characters" }
        }
    }

    private fun indent(
        source: String,
        indent: String,
    ): String {
        if (indent.isEmpty()) return source
        val lines = source.split('\n')
        return lines.mapIndexed { i, line -> if (line.isEmpty() && i == lines.lastIndex) line else indent + line }.joinToString("\n")
    }

    private fun lookup(
        stack: List<JsonElement>,
        name: String,
    ): JsonElement? {
        if (name == ".") return stack.last()
        val parts = name.split('.')
        val head = stack.asReversed().firstNotNullOfOrNull { (it as? JsonObject)?.get(parts.first()) } ?: return null
        return parts.drop(1).fold(head as JsonElement?) { acc, key -> (acc as? JsonObject)?.get(key) }
    }

    private fun truthy(value: JsonElement?): Boolean =
        when (value) {
            null, JsonNull -> false
            is JsonArray -> value.isNotEmpty()
            is JsonObject -> true
            is JsonPrimitive -> value.booleanOrNull ?: if (value.isString) value.content.isNotEmpty() else value.doubleOrNull != 0.0
        }

    private fun text(value: JsonElement): String =
        when (value) {
            JsonNull -> ""
            is JsonPrimitive -> value.content
            else -> value.toString()
        }
}
