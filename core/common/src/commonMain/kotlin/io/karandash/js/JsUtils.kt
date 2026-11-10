package io.karandash.js

import io.karandash.models.support.KarandashJson

/**
 * Lightweight utility functions for JavaScript plugin interop.
 *
 * Provides string escaping for safe embedding in JS code and
 * JSON deserialization helpers used by JS plugin bridges.
 */
object JsUtils {
    private val json = KarandashJson.Plain

    /**
     * Escape string for safe embedding in a quoted JavaScript string literal.
     * Handles backslashes, quotes, every control character and the U+2028/U+2029 line terminators.
     */
    fun escapeForJs(s: String): String = buildString(s.length) { appendEscaped(s, escapeSingleQuote = true) }

    /**
     * [s] as a quoted JSON string literal, also safe to embed in JavaScript source: the [escapeForJs] escapes
     * minus `\'`, which JSON does not allow.
     */
    fun quoteJson(s: String): String =
        buildString {
            append('"')
            appendEscaped(s, escapeSingleQuote = false)
            append('"')
        }

    private fun StringBuilder.appendEscaped(
        s: String,
        escapeSingleQuote: Boolean,
    ) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> if (escapeSingleQuote) append("\\'") else append(ch)
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ' || ch == ' ' || ch == ' ') appendUnicodeEscape(ch) else append(ch)
            }
        }
    }

    private fun StringBuilder.appendUnicodeEscape(ch: Char) {
        append("\\u").append(ch.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
    }

    private const val HEX_RADIX = 16
    private const val UNICODE_ESCAPE_DIGITS = 4

    /**
     * Parse JSON string to list of strings.
     */
    fun stringListFromJson(jsonStr: String): List<String> = json.decodeFromString(jsonStr)
}
