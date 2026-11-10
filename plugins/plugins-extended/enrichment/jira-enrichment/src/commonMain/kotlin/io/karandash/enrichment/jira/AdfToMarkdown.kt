package io.karandash.enrichment.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Converts Atlassian Document Format (ADF) JSON to plain markdown.
 *
 * ADF is a JSON-based document model - this converter never parses XML or HTML
 * and is not subject to XXE. Unknown node types produce an
 * `[unsupported: <type>]` placeholder so the converter never throws on
 * unexpected input.
 *
 * Handles: `doc`, `paragraph`, `heading`, `bulletList`, `orderedList`,
 * `listItem`, `codeBlock`, `blockquote`, `rule`, `text` (with `strong`, `em`,
 * `code`, `strike`, `link` marks), `mention`, `emoji`, `hardBreak`.
 */
internal object AdfToMarkdown {
    fun convert(adf: JsonElement?): String {
        if (adf == null || adf is JsonNull) return ""
        val root = adf as? JsonObject ?: return ""
        val sb = StringBuilder()
        renderBlock(root, sb, depth = 0)
        return sb.toString().trim()
    }

    private fun renderBlock(
        node: JsonObject,
        sb: StringBuilder,
        depth: Int
    ) {
        when (val type = node.nodeType()) {
            "doc" -> {
                childrenOf(node).forEach { renderBlock(it, sb, depth) }
            }

            "paragraph" -> {
                childrenOf(node).forEach { renderInline(it, sb) }
                sb.append('\n')
            }

            "heading" -> {
                val level = node.attrInt("level")?.coerceIn(1, 6) ?: 1
                sb.append("#".repeat(level)).append(' ')
                childrenOf(node).forEach { renderInline(it, sb) }
                sb.append('\n')
            }

            "codeBlock" -> {
                val lang = node.attrStr("language") ?: ""
                sb.append("```$lang\n")
                childrenOf(node).forEach { renderInline(it, sb) }
                if (!sb.endsWith('\n')) sb.append('\n')
                sb.append("```\n")
            }

            "bulletList" -> {
                childrenOf(node).forEach { renderListItem(it, sb, depth, false) }
            }

            "orderedList" -> {
                childrenOf(node).forEach { renderListItem(it, sb, depth, true) }
            }

            "blockquote" -> {
                renderBlockquote(node, sb, depth)
            }

            "rule" -> {
                sb.append("\n---\n")
            }

            null -> {
                Unit
            }

            else -> {
                sb.append("[unsupported: $type]\n")
            }
        }
    }

    private fun renderBlockquote(
        node: JsonObject,
        sb: StringBuilder,
        depth: Int
    ) {
        val inner = StringBuilder()
        childrenOf(node).forEach { renderBlock(it, inner, depth) }
        inner.toString().lines().dropLastWhile { it.isBlank() }.forEach {
            sb.append("> ").append(it).append('\n')
        }
    }

    private fun renderListItem(
        node: JsonObject,
        sb: StringBuilder,
        depth: Int,
        ordered: Boolean
    ) {
        if (node.nodeType() != "listItem") return
        val prefix = "${"  ".repeat(depth)}${if (ordered) "1." else "-"} "
        var headerWritten = false
        childrenOf(node).forEach { child ->
            if (headerWritten && !sb.endsWith('\n')) sb.append('\n')
            when (child.nodeType()) {
                "bulletList" -> {
                    childrenOf(child).forEach { renderListItem(it, sb, depth + 1, false) }
                }

                "orderedList" -> {
                    childrenOf(child).forEach { renderListItem(it, sb, depth + 1, true) }
                }

                else -> {
                    sb.append(if (headerWritten) " ".repeat(prefix.length) else prefix)
                    headerWritten = true
                    childrenOf(child).forEach { renderInline(it, sb) }
                }
            }
        }
        if (!sb.endsWith('\n')) sb.append('\n')
    }

    private fun renderInline(
        node: JsonObject,
        sb: StringBuilder
    ) {
        when (val type = node.nodeType()) {
            "text" -> {
                val raw = node["text"].stringOrNull() ?: return
                sb.append(applyMarks(raw, node["marks"] as? JsonArray))
            }

            "mention" -> {
                sb.append("@user")
            }

            "emoji" -> {
                sb.append(node.attrStr("text") ?: ":emoji:")
            }

            "hardBreak" -> {
                sb.append('\n')
            }

            null -> {
                Unit
            }

            else -> {
                sb.append("[unsupported: $type]")
            }
        }
    }

    private fun applyMarks(
        text: String,
        marks: JsonArray?
    ): String {
        if (marks == null) return text
        var result = text
        for (mark in marks.reversed()) {
            val m = mark as? JsonObject ?: continue
            result = when (m.nodeType()) {
                "strong" -> "**$result**"
                "em" -> "_${result}_"
                "code" -> "`$result`"
                "strike" -> "~~$result~~"
                "link" -> "[$result](${m.attrStr("href") ?: ""})"
                else -> result
            }
        }
        return result
    }

    private fun childrenOf(node: JsonObject): List<JsonObject> =
        (node["content"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

    private fun JsonObject.nodeType(): String? = this["type"].stringOrNull()

    private fun JsonObject.attrStr(key: String): String? = (this[ATTRS] as? JsonObject)?.get(key).stringOrNull()

    /** A field of the wrong JSON shape reads as absent, so malformed ADF never throws. */
    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.attrInt(key: String): Int? = attrStr(key)?.toIntOrNull()
}

private const val ATTRS = "attrs"
