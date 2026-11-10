package io.karandash.cli.commands.stats.audit

import kotlinx.serialization.Serializable

/**
 * Anchor check of one bot inline note. [accurate] only counts when [quotesCode]: the anchor line is a code
 * line and the quoted code occurs within ±[AnchorCheck.WINDOW] lines of it.
 */
@Serializable
data class AnchorResult(
    val quotesCode: Boolean,
    val accurate: Boolean,
    val nonCodeLine: Boolean,
)

/** Automated anchor accuracy: compares the code a note quotes with the file lines around its anchor. */
object AnchorCheck {
    const val WINDOW = 3
    private const val FENCE = "```"
    private const val HEADER_RULE = "** — `"
    private const val BRACKETS = "{}()[];,"
    private val COMMENT_PREFIXES = listOf("//", "#", "/*", "*", "<!--", "--")

    /** `null` when the note has no anchor snippet or the anchor line is outside it. */
    fun check(note: CorpusNote): AnchorResult? {
        val anchor = note.anchor ?: return null
        val line = note.position?.newLine ?: return null
        val index = line - anchor.startLine
        if (index !in anchor.snippet.indices) return null
        val nonCode = !isCodeLine(anchor.snippet[index])
        val quoted = quotedCode(note.body) ?: return AnchorResult(quotesCode = false, accurate = false, nonCodeLine = nonCode)
        val window = anchor.snippet.subList((index - WINDOW).coerceAtLeast(0), (index + WINDOW + 1).coerceAtMost(anchor.snippet.size))
        val found = window.any { quoted in it.trim() }
        return AnchorResult(quotesCode = true, accurate = found && !nonCode, nonCodeLine = nonCode)
    }

    /** The karandash header line minus its badge and rule id: the terse title, or "" for a header-only line. */
    private fun withoutHeader(line: String): String {
        if (!line.trimStart().startsWith("**") || HEADER_RULE !in line) return line
        val ruleEnd = line.indexOf('`', line.indexOf(HEADER_RULE) + HEADER_RULE.length)
        return if (ruleEnd < 0) "" else line.substring(ruleEnd + 1)
    }

    /** Blank, only brackets/punctuation, or a comment line is not code. */
    fun isCodeLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.all { it in BRACKETS }) return false
        return COMMENT_PREFIXES.none { t.startsWith(it) }
    }

    /**
     * First non-blank line of the first fenced block, else the first inline backtick span, else `null`. The karandash
     * header prefix (`**WARNING** - `rule-id``) is stripped first, so the rule id is not taken as quoted code while
     * code quoted in the title after it still is.
     */
    fun quotedCode(note: String): String? {
        val body = note.lines().joinToString("\n", transform = ::withoutHeader)
        val fence = body.indexOf(FENCE)
        if (fence >= 0) {
            val end = body.indexOf(FENCE, fence + FENCE.length).let { if (it < 0) body.length else it }
            val firstLine = body
                .substring(fence + FENCE.length, end)
                .lines()
                .drop(1)
                .firstOrNull { it.isNotBlank() }
            if (firstLine != null) return firstLine.trim()
        }
        val open = body.indexOf('`')
        val close = if (open >= 0) body.indexOf('`', open + 1) else -1
        if (close <= open + 1) return null
        return body.substring(open + 1, close).trim().ifEmpty { null }
    }
}
