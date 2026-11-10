package io.karandash.plugins.parsers.yaml

/**
 * yamlkt 0.13 rejects plain scalars that contain a colon not followed by a space (`url: https://x`,
 * `- https://x`, `at: 12:30`), which YAML allows. This double-quotes such values in block mappings and
 * block sequences before parsing. Quoted values, flow collections (apart from the marker below), anchors/aliases/tags, block scalar
 * (`|`, `>`) bodies and comment lines are left as written.
 *
 * It double-quotes plain values holding a `${VAR}` reference too, in block lines and as whole items of single-line flow
 * collections: yamlkt reads the `{` as a flow mapping, so `token: ${TOKEN}` came out as `"$"`.
 *
 * It also tags quoted scalars that read as a number, boolean or null (`"007"`, `'true'`) with [QUOTED_MARKER]: yamlkt
 * drops the quotes, so without the tag `"007"` would come out as the number 7. The YAML format module strips
 * the tag and keeps the value a string.
 */
internal object ColonScalarQuoter {
    private const val SEQUENCE_PREFIX = "- "
    private const val KEY_SEPARATOR = ": "
    private const val COMMENT_START = " #"
    private const val NON_PLAIN_STARTS = "#'\"[{|>&*!%@`"
    private const val BLOCK_SCALAR_STARTS = "|>"
    private const val ENV_REFERENCE = "${'$'}{"

    /** A quoted number, boolean or null literal (the pattern stays a literal for karandash:DynamicRegex). */
    private val QUOTED_NON_STRING = Regex("""(["'])([-+]?\d+(\.\d+)?|true|false|null|~)\1""")

    /** A quoted non-string literal that is a whole item of a single-line flow collection (`["1", a: '2']`). */
    private val FLOW_QUOTED_NON_STRING = Regex("""([\[{,:]\s*)(["'])([-+]?\d+(\.\d+)?|true|false|null|~)\2(?=\s*[,\]}])""")

    /** An unquoted item of a single-line flow collection that starts with a `${VAR}` reference (`[${A}, b]`, `{k: ${A}}`). */
    private val FLOW_ENV_ITEM = Regex("""([\[{,]\s*|:\s+)(\$\{[^}]*\}(?:\$\{[^}]*\}|[^,\[\]{}\s$])*)(?=\s*[,\]}])""")

    /** Prefix of a quoted scalar that must stay a string; see the class KDoc. */
    const val QUOTED_MARKER = "__karandash_quoted__"

    fun quote(yaml: String): String {
        val out = mutableListOf<String>()
        yaml.lines().fold(null as Int?) { blockScalarIndent, line ->
            val indent = line.length - line.trimStart().length
            if (blockScalarIndent != null && (line.isBlank() || indent > blockScalarIndent)) {
                out += line
                return@fold blockScalarIndent
            }
            val start = valueStart(line)
            val value = start?.let { line.substring(it).substringBefore(COMMENT_START).trimEnd() }.orEmpty()
            out += when {
                start == null -> line

                needsQuoting(value) -> quoteValue(line, start, value)

                QUOTED_NON_STRING.matches(value) -> line.substring(0, start) + "\"$QUOTED_MARKER${value.substring(1, value.length - 1)}\"" +
                    line.substring(start + value.length)

                value.startsWith("[") || value.startsWith("{") -> line.substring(0, start) + tagFlowItems(value) +
                    line.substring(start + value.length)

                else -> line
            }
            if (value.firstOrNull()?.let { it in BLOCK_SCALAR_STARTS } == true) indent else null
        }
        return out.joinToString("\n")
    }

    private fun tagFlowItems(value: String): String =
        FLOW_QUOTED_NON_STRING
            .replace(value) { "${it.groupValues[1]}\"$QUOTED_MARKER${it.groupValues[3]}\"" }
            .let { tagged -> FLOW_ENV_ITEM.replace(tagged) { "${it.groupValues[1]}\"${escape(it.groupValues[2])}\"" } }

    /** Index where the value of a `key: value` or `- value` line starts, or null when the line has none. */
    private fun valueStart(line: String): Int? {
        val trimmed = line.trimStart()
        val body = if (trimmed.startsWith(SEQUENCE_PREFIX)) trimmed.drop(SEQUENCE_PREFIX.length).trimStart() else trimmed
        if (body.isEmpty() || body[0] == '#') return null
        val separator = body.indexOf(KEY_SEPARATOR)
        val raw = when {
            separator > 0 -> body.substring(separator + KEY_SEPARATOR.length)
            body.length != trimmed.length -> body
            else -> return null
        }
        return line.length - raw.trimStart().length
    }

    private fun needsQuoting(value: String): Boolean =
        value.isNotEmpty() && value[0] !in NON_PLAIN_STARTS && KEY_SEPARATOR !in value &&
            (ENV_REFERENCE in value || (':' in value && !value.endsWith(':')))

    private fun quoteValue(
        line: String,
        start: Int,
        value: String,
    ): String = line.substring(0, start) + "\"" + escape(value) + "\"" + line.substring(start + value.length)

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
