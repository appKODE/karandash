package io.karandash.models.template

import kotlinx.serialization.json.JsonPrimitive

/**
 * The escapers a [Mustache] template can use: as the default for `{{name}}`, or as a section lambda over raw content
 * (`{{#md}}{{{text}}}{{/md}}`, `{{#html}}…`, `{{#slack}}…`, `{{#json}}…`).
 */
public object TemplateEscapes {
    private const val AMP = "&amp;"
    private const val LT = "&lt;"
    private const val GT = "&gt;"

    /** HTML text and attribute values: `& < > " '`. */
    public fun html(text: String): String =
        text
            .replace("&", AMP)
            .replace("<", LT)
            .replace(">", GT)
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    /**
     * One line of GitLab/GitHub Markdown (a table cell or a heading): line breaks become spaces, and Markdown, KaTeX,
     * mention and HTML syntax (emphasis `* _ ~`, entities `&`) is neutralised. A code span (`` `x` `` or ``` `` x `` ```) stays code: only its `|` is
     * escaped, so it cannot end a table cell, and nothing in it is rendered anyway.
     */
    public fun markdown(text: String): String {
        val oneLine = text.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
        val codes = CODE_SPAN.findAll(oneLine).map { it.value.replace("|", "\\|") }.toList()
        return oneLine.split(CODE_SPAN).mapIndexed { i, prose -> markdownProse(prose) + codes.getOrElse(i) { "" } }.joinToString("")
    }

    private val CODE_SPAN = Regex("`` .+? ``|`[^`]+`")

    private fun markdownProse(text: String): String =
        text
            .replace("\\", "\\\\")
            .replace("&", AMP)
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("~", "\\~")
            .replace("@", "@​")
            .replace("#", "\\#")
            .replace("`", "\\`")
            .replace("|", "\\|")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("$", "\\$")
            .replace("<", LT)
            .replace(">", GT)

    /** Slack mrkdwn text: the three control characters `& < >`. */
    public fun slack(text: String): String =
        text
            .replace("&", AMP)
            .replace("<", LT)
            .replace(">", GT)

    /** The content of a JSON string literal, without the surrounding quotes. */
    public fun json(text: String): String = JsonPrimitive(text).toString().let { it.substring(1, it.length - 1) }

    /** No escaping: plain-text media such as a terminal or an email text part. */
    public fun none(text: String): String = text

    /** The section lambdas every template can use, by name. */
    public val LAMBDAS: Map<String, (String) -> String> =
        mapOf("md" to ::markdown, "html" to ::html, "slack" to ::slack, "json" to ::json)

    /**
     * The `{{name}}` escapers by name, as `escape:` (template format) and `format render --escape` accept them: the
     * [LAMBDAS] names plus `markdown` (alias of `md`) and `none`.
     */
    public val BY_NAME: Map<String, (String) -> String> = LAMBDAS + mapOf("markdown" to ::markdown, "none" to ::none)

    /** The escaper [name] (case-insensitive) names in [BY_NAME]; `null` for an unknown name. */
    public fun byName(name: String): ((String) -> String)? = BY_NAME[name.lowercase()]
}
