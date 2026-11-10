package io.karandash.helpers.reporter

import io.karandash.models.template.TemplateEscapes

/** This text safe in HTML element content and in quoted attribute values (`&`, `<`, `>`, `"`, `'`). */
fun String.escapeHtml(): String = TemplateEscapes.html(this)

/** Escapes markup and drops what XML 1.0 forbids: control characters, U+FFFE/U+FFFF and lone surrogates. */
fun String.escapeXml(): String =
    withoutInvalidXmlChars()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

private fun String.withoutInvalidXmlChars(): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        val pair = c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()
        when {
            pair -> {
                out.append(c).append(this[i + 1])
                i++
            }

            c.isSurrogate() || c == '￾' || c == '￿' -> {
                Unit
            }

            c >= ' ' || c == '\t' || c == '\n' || c == '\r' -> {
                out.append(c)
            }
        }
        i++
    }
    return out.toString()
}
