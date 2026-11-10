package io.karandash.enrichment.confluence

/**
 * Converts Confluence storage-format XML to plain markdown.
 *
 * Security: `<!DOCTYPE>` and `<!ENTITY>` declarations are silently ignored
 * (XXE protection). Input is capped at [MAX_INPUT_CHARS] before processing
 * (billion-laughs / giant-payload defense). Only built-in XML entities
 * (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, `&nbsp;`) are expanded.
 *
 * Supported elements: `<h1>`–`<h6>`, `<p>`, `<strong>`, `<b>`, `<em>`, `<i>`,
 * `<code>`, `<pre>`, `<ul>`, `<ol>`, `<li>`, `<a href>`, `<br>`,
 * `<ac:structured-macro>` (rendered as `[macro: name]`),
 * `<![CDATA[...]]>` (raw text). Unknown elements are transparent (content kept).
 */
internal object StorageXmlToMarkdown {
    internal const val MAX_INPUT_CHARS = 500_000
    private const val BULLET = -1

    private val ATTR_REGEX = Regex("""(\S+?)=["']([^"']*)["']""")

    fun convert(xml: String): String {
        if (xml.isBlank()) return ""
        val tokens = tokenize(xml.take(MAX_INPUT_CHARS))
        val sb = StringBuilder()
        render(tokens, sb)
        return sb.toString().trim()
    }

    private fun tokenize(input: String): List<XmlToken> {
        val result = mutableListOf<XmlToken>()
        var i = 0
        val text = StringBuilder()

        fun flush() {
            if (text.isNotEmpty()) {
                result.add(XmlToken.Text(decodeEntities(text.toString())))
                text.clear()
            }
        }

        while (i < input.length) {
            if (input[i] != '<') {
                text.append(input[i++])
                continue
            }
            flush()
            when {
                input.startsWith("<!--", i) -> {
                    val end = input.indexOf("-->", i + 4)
                    i = if (end >= 0) end + 3 else input.length
                }

                input.startsWith("<![CDATA[", i) -> {
                    val end = input.indexOf("]]>", i + 9)
                    val raw = if (end >= 0) input.substring(i + 9, end) else input.substring(i + 9)
                    result.add(XmlToken.Text(raw))
                    i = if (end >= 0) end + 3 else input.length
                }

                input.startsWith("<!", i) -> {
                    val end = input.indexOf('>', i + 2)
                    i = if (end >= 0) end + 1 else input.length
                }

                input.startsWith("</", i) -> {
                    val end = input.indexOf('>', i + 2)
                    val name = if (end >= 0) input.substring(i + 2, end).trim() else ""
                    if (name.isNotEmpty()) result.add(XmlToken.CloseTag(name.lowercase()))
                    i = if (end >= 0) end + 1 else input.length
                }

                else -> {
                    val end = input.indexOf('>', i + 1)
                    val inner = if (end >= 0) input.substring(i + 1, end) else input.substring(i + 1)
                    val selfClosing = inner.trimEnd().endsWith("/")
                    val content = if (selfClosing) inner.trimEnd().dropLast(1).trim() else inner.trim()
                    val spaceIdx = content.indexOfFirst { it.isWhitespace() }
                    val tagName = (if (spaceIdx >= 0) content.substring(0, spaceIdx) else content).lowercase()
                    val attrsStr = if (spaceIdx >= 0) content.substring(spaceIdx + 1) else ""
                    if (tagName.isNotEmpty()) {
                        val attrs = parseAttrs(attrsStr)
                        result.add(XmlToken.OpenTag(tagName, attrs, selfClosing))
                        if (selfClosing) result.add(XmlToken.CloseTag(tagName))
                    }
                    i = if (end >= 0) end + 1 else input.length
                }
            }
        }
        flush()
        return result
    }

    private fun parseAttrs(attrsStr: String): Map<String, String> {
        if (attrsStr.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        ATTR_REGEX.findAll(attrsStr).forEach { m -> result[m.groupValues[1]] = m.groupValues[2] }
        return result
    }

    private fun decodeEntities(text: String): String =
        text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&") // last, so `&amp;lt;` stays the literal text `&lt;`

    private fun render(
        tokens: List<XmlToken>,
        sb: StringBuilder
    ) {
        val lists = ArrayDeque<Int>() // per open list: next item number, or BULLET for <ul>
        val links = ArrayDeque<String?>()
        var inPre = false
        var skipDepth = 0

        for (token in tokens) {
            when (token) {
                is XmlToken.OpenTag -> when {
                    skipDepth > 0 -> {
                        skipDepth++
                    }

                    token.name == "ac:structured-macro" -> {
                        sb.append("[macro: ${token.attrs["ac:name"] ?: "macro"}]\n")
                        skipDepth = 1
                    }

                    else -> {
                        if (token.name == "pre") inPre = true
                        applyOpenTag(token, sb, lists, inPre)
                        if (token.name == "a") openLink(token, sb, links)
                    }
                }

                is XmlToken.CloseTag -> when {
                    skipDepth > 0 -> {
                        skipDepth--
                    }

                    else -> {
                        if (token.name == "pre") inPre = false
                        applyCloseTag(token, sb, lists, inPre)
                        if (token.name == "a") links.removeLastOrNull()?.let { sb.append("]($it)") }
                    }
                }

                is XmlToken.Text -> if (skipDepth == 0) sb.append(token.content)
            }
        }
    }

    private fun openLink(
        token: XmlToken.OpenTag,
        sb: StringBuilder,
        links: ArrayDeque<String?>,
    ) {
        val href = token.attrs["href"]?.let(::decodeEntities)?.takeIf { it.isNotBlank() }
        links.addLast(href)
        if (href != null) sb.append('[')
    }

    private fun applyOpenTag(
        token: XmlToken.OpenTag,
        sb: StringBuilder,
        lists: ArrayDeque<Int>,
        inPre: Boolean,
    ) {
        when (token.name) {
            "h1" -> {
                sb.append("# ")
            }

            "h2" -> {
                sb.append("## ")
            }

            "h3" -> {
                sb.append("### ")
            }

            "h4" -> {
                sb.append("#### ")
            }

            "h5" -> {
                sb.append("##### ")
            }

            "h6" -> {
                sb.append("###### ")
            }

            "strong", "b" -> {
                sb.append("**")
            }

            "em", "i" -> {
                sb.append("_")
            }

            "code" -> {
                if (!inPre) sb.append("`")
            }

            "pre" -> {
                sb.append("```\n")
            }

            "ul" -> {
                lists.addLast(BULLET)
            }

            "ol" -> {
                lists.addLast(1)
            }

            "li" -> {
                val number = lists.lastOrNull() ?: BULLET
                sb.append("  ".repeat((lists.size - 1).coerceAtLeast(0)))
                if (number == BULLET) {
                    sb.append("- ")
                } else {
                    sb.append("$number. ")
                    lists[lists.lastIndex] = number + 1
                }
            }

            "br" -> {
                sb.append('\n')
            }
        }
    }

    private fun applyCloseTag(
        token: XmlToken.CloseTag,
        sb: StringBuilder,
        lists: ArrayDeque<Int>,
        inPre: Boolean,
    ) {
        when (token.name) {
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "li" -> sb.append('\n')
            "strong", "b" -> sb.append("**")
            "em", "i" -> sb.append("_")
            "code" -> if (!inPre) sb.append("`")
            "pre" -> sb.append(if (sb.endsWith('\n')) "```\n" else "\n```\n")
            "ul", "ol" -> lists.removeLastOrNull()
        }
    }
}

internal sealed interface XmlToken {
    class OpenTag(
        val name: String,
        val attrs: Map<String, String>,
        val selfClosing: Boolean
    ) : XmlToken

    class CloseTag(
        val name: String
    ) : XmlToken

    class Text(
        val content: String
    ) : XmlToken
}
