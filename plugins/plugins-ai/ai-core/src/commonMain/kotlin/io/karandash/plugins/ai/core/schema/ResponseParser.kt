package io.karandash.plugins.ai.core.schema

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement

/**
 * Owns the repair of malformed LLM JSON ([extractJson]), which runs only as a fallback when a reply
 * is not valid JSON already.
 */
object ResponseParser {
    /**
     * Regex for extracting JSON from markdown code blocks.
     *
     * Matches both ` ```json ... ``` ` and bare ` ``` ... ``` ` blocks.
     */
    internal val CODE_BLOCK_REGEX = Regex("""```(?:json)?\s*\n?([\s\S]*?)\n?```""")

    /** Matches `<think>...</think>` and similar reasoning blocks emitted by reasoning models. */
    internal val THINKING_BLOCK_REGEX = Regex(
        """<(?:think|thinking|reasoning|reflection)>[\s\S]*?</(?:think|thinking|reasoning|reflection)>""",
        RegexOption.IGNORE_CASE,
    )

    /** Matches trailing commas before a closing brace or bracket. */
    private val TRAILING_COMMA_REGEX = Regex(""",\s*([}\]])""")

    /** Matches single-line `// ...` comments outside strings (not valid JSON but produced by some LLMs). */
    private val SINGLE_LINE_COMMENT_REGEX = Regex("""(?<=\n|^)\s*//[^\n]*""", RegexOption.MULTILINE)

    /**
     * Returns the JSON payload of an LLM reply. A reply that is already a JSON object or array (as structured
     * output guarantees) is returned untouched; only otherwise is it repaired, in this order: strip reasoning
     * blocks, take a markdown fence, else the first balanced `{...}`/`[...]`, else close the brackets of a
     * truncated reply, else the trimmed text; then drop trailing commas and `//` comment lines and escape
     * literal newlines inside strings.
     */
    fun extractJson(content: String): String {
        val trimmed = content.trim()
        if (isJsonContainer(trimmed)) return trimmed
        // Strip thinking/reasoning blocks that some models emit before JSON
        val stripped = THINKING_BLOCK_REGEX.replace(content, "").trim()
        val input = stripped.ifEmpty { content.trim() }

        val fenceMatch = CODE_BLOCK_REGEX.find(input)
        if (fenceMatch != null) {
            return cleanJsonString(fenceMatch.groupValues[1].trim())
        }

        val extracted = extractFirstJsonBlock(input)
        if (extracted != null) {
            return cleanJsonString(extracted)
        }

        val recovered = recoverTruncatedJson(input)
        if (recovered != null) {
            return cleanJsonString(recovered)
        }

        return cleanJsonString(input)
    }

    private fun isJsonContainer(text: String): Boolean =
        (text.startsWith('{') || text.startsWith('[')) &&
            try {
                KarandashJson.Plain.parseToJsonElement(text)
                true
            } catch (_: SerializationException) {
                false // not valid JSON as-is: the caller repairs it
            }

    /**
     * Removes trailing commas before `}` or `]` - a common artifact in LLM-generated JSON.
     * Also escapes literal newlines inside string values, which LLMs sometimes emit instead of `\n`.
     */
    private fun cleanJsonString(json: String): String {
        var result = TRAILING_COMMA_REGEX.replace(json) { match ->
            match.groupValues[1]
        }
        // Strip single-line // comments (not valid JSON but produced by some LLMs)
        result = SINGLE_LINE_COMMENT_REGEX.replace(result, "")
        result = escapeLiteralNewlinesInStrings(result)
        return result
    }

    /**
     * Replaces literal newline and carriage-return characters inside JSON string values
     * with their JSON-escaped equivalents (`\n`, `\r`).
     *
     * Some LLMs emit multi-line string values with actual newline characters rather than
     * the `\n` escape sequence required by the JSON spec, causing parse failures.
     */
    private fun escapeLiteralNewlinesInStrings(json: String): String {
        val sb = StringBuilder(json.length)
        var inString = false
        var escaped = false
        for (ch in json) {
            when {
                escaped -> {
                    escaped = false
                    sb.append(ch)
                }

                ch == '\\' && inString -> {
                    escaped = true
                    sb.append(ch)
                }

                ch == '"' -> {
                    inString = !inString
                    sb.append(ch)
                }

                inString && ch == '\n' -> {
                    sb.append("\\n")
                }

                inString && ch == '\r' -> {
                    sb.append("\\r")
                }

                else -> {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    /**
     * Attempts to recover truncated JSON by appending missing closing brackets/braces.
     *
     * LLMs sometimes truncate output mid-JSON when hitting token limits. This heuristic
     * counts open vs close brackets and appends the needed closers.
     *
     * @return Recovered JSON string, or null if the input doesn't look like truncated JSON.
     */
    internal fun recoverTruncatedJson(text: String): String? {
        val startIdx = text.indexOfFirst { it == '{' || it == '[' }
        if (startIdx < 0) return null

        val fragment = text.substring(startIdx)
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false

        for (ch in fragment) {
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (ch) {
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }

        if (stack.isEmpty()) return null

        val closingBrackets = stack.reversed().joinToString("")
        return if (inString) {
            // Truncated inside a string value - close the quote before appending brackets
            "$fragment\"$closingBrackets"
        } else {
            fragment + closingBrackets
        }
    }

    /**
     * Extracts the first balanced JSON object `{...}` or array `[...]` from text that may
     * contain surrounding prose. Returns null if no balanced block is found.
     */
    internal fun extractFirstJsonBlock(text: String): String? {
        val startIdx = text.indexOfFirst { it == '{' || it == '[' }
        if (startIdx < 0) return null

        val openChar = text[startIdx]
        val closeChar = if (openChar == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false

        for (i in startIdx until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            if (ch == openChar) {
                depth++
            } else if (ch == closeChar) {
                depth--
            }

            if (depth == 0) {
                return text.substring(startIdx, i + 1)
            }
        }
        return null
    }
}
