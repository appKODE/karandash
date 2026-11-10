package io.karandash.models.template

/** A parsed Mustache template node. */
internal sealed interface MustacheNode {
    class Text(
        val text: String,
    ) : MustacheNode

    class Variable(
        val name: String,
        val escaped: Boolean,
    ) : MustacheNode

    class Section(
        val name: String,
        val inverted: Boolean,
        val children: List<MustacheNode>,
    ) : MustacheNode

    class Partial(
        val name: String,
        val indent: String,
    ) : MustacheNode
}

/** Parses one template source into [MustacheNode]s; see [Mustache] for the supported subset. */
internal class MustacheParser(
    private val src: String,
) {
    private class Frame(
        val name: String,
        val inverted: Boolean,
    ) {
        val children = mutableListOf<MustacheNode>()
    }

    fun parse(): List<MustacheNode> {
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame("", inverted = false))
        var pos = 0
        while (pos < src.length) {
            val open = src.indexOf(OPEN, pos)
            if (open < 0) {
                stack.last().children.addText(src.substring(pos))
                break
            }
            val tag = readTag(open)
            val standalone = tag.kind in STANDALONE_KINDS && standaloneBounds(pos, open, tag.end)
            val lineStart = src.lastIndexOf('\n', open - 1) + 1
            stack.last().children.addText(src.substring(pos, if (standalone) lineStart else open))
            pos = if (standalone) lineAfter(tag.end) else tag.end
            apply(tag, stack, if (standalone) src.substring(lineStart, open) else "")
        }
        require(stack.size == 1) { "Unclosed section '${stack.last().name}'" }
        return stack.single().children
    }

    private class Tag(
        val kind: Char,
        val name: String,
        val end: Int,
    )

    private fun readTag(open: Int): Tag {
        val triple = src.startsWith("{{{", open)
        val closeToken = if (triple) "}}}" else CLOSE
        val bodyStart = open + if (triple) TRIPLE_LENGTH else OPEN.length
        val close = src.indexOf(closeToken, bodyStart)
        require(close >= 0) { "Unclosed tag at offset $open" }
        val body = src.substring(bodyStart, close).trim()
        val kind = if (triple) '&' else body.firstOrNull()?.takeIf { it in SIGILS } ?: ' '
        val name = if (kind == ' ' || triple) body else body.drop(1).trim()
        require(kind != '=') { "Set-delimiter tags are not supported (offset $open)" }
        require(kind == '!' || name.isNotEmpty()) { "Empty tag at offset $open" }
        return Tag(kind, name, close + closeToken.length)
    }

    private fun apply(
        tag: Tag,
        stack: ArrayDeque<Frame>,
        indent: String,
    ) {
        val children = stack.last().children
        when (tag.kind) {
            '!' -> {
                Unit
            }

            '#', '^' -> {
                stack.addLast(Frame(tag.name, inverted = tag.kind == '^'))
            }

            '/' -> {
                val frame = stack.removeLast()
                require(stack.isNotEmpty() && frame.name == tag.name) { "Unexpected closing tag '${tag.name}'" }
                stack.last().children.add(MustacheNode.Section(frame.name, frame.inverted, frame.children))
            }

            '>' -> {
                children.add(MustacheNode.Partial(tag.name, indent))
            }

            '&' -> {
                children.add(MustacheNode.Variable(tag.name, escaped = false))
            }

            else -> {
                children.add(MustacheNode.Variable(tag.name, escaped = true))
            }
        }
    }

    /** True when only whitespace sits between the line start and [open], and between [end] and the line end. */
    private fun standaloneBounds(
        pos: Int,
        open: Int,
        end: Int,
    ): Boolean {
        val lineStart = src.lastIndexOf('\n', open - 1) + 1
        if (lineStart < pos) return false
        val lineEnd = src.indexOf('\n', end).let { if (it < 0) src.length else it }
        return src.substring(lineStart, open).isBlank() && src.substring(end, lineEnd).isBlank()
    }

    private fun lineAfter(end: Int): Int = src.indexOf('\n', end).let { if (it < 0) src.length else it + 1 }

    private fun MutableList<MustacheNode>.addText(text: String) {
        if (text.isNotEmpty()) add(MustacheNode.Text(text))
    }

    private companion object {
        const val OPEN = "{{"
        const val CLOSE = "}}"
        const val TRIPLE_LENGTH = 3
        const val SIGILS = "#^/>&!="
        val STANDALONE_KINDS = setOf('#', '^', '/', '>', '!')
    }
}
