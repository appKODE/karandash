package io.karandash.utils

/**
 * The one glob → regex translator behind `GlobMatcher` (core/glob), [CollectionMatcher] and `TextGlobCompiler`
 * (core/multipattern). It lives in core/common because core/glob already depends on core/common, so a translator in
 * core/glob could not serve [CollectionMatcher] without a cycle.
 *
 * Each caller keeps its own [Mode]; the dialects differ on purpose and `GlobParityTest` freezes their results.
 * The result is an anchored (`^…$`) regex source; compiling it (and any size/safety check) stays with the caller.
 */
object GlobTranslator {
    /** Glob dialect. */
    enum class Mode {
        /** File paths: `*`/`?` stop at `/`, `**` crosses it; braces and brackets are literals. */
        PATH,

        /** Plain strings: `*` is any run, `?` any one char (both cross `/`); everything else is literal. */
        WILDCARD,

        /** Text: [WILDCARD] plus `{a,b}` alternation (nestable) and `[abc]` / `[!abc]` classes. */
        TEXT,
    }

    /** Translates [glob] to an anchored regex source in [mode]. */
    fun toRegexSource(
        glob: String,
        mode: Mode,
    ): String =
        when (mode) {
            Mode.PATH -> "^" + pathBody(glob) + "$"
            Mode.WILDCARD -> "^" + wildcardBody(glob) + "$"
            Mode.TEXT -> "^" + textBody(glob) + "$"
        }

    private const val LEADING = "<!LEADING_DOUBLESTAR!>"
    private const val TRAILING = "<!TRAILING_DOUBLESTAR!>"
    private const val MIDDLE = "<!MIDDLE_DOUBLESTAR!>"
    private const val DOUBLESTAR = "<!DOUBLESTAR!>"
    private const val STAR = "<!STAR!>"
    private const val QUESTION = "<!QUESTION!>"
    private const val PATH_META = "\\.+^\${}[]()|"

    /** Placeholders first, then escape regex metacharacters, then restore placeholders as path-aware regex. */
    private fun pathBody(pattern: String): String {
        var regex = pattern
        if (regex.startsWith("**/")) regex = LEADING + regex.substring(3)
        if (regex.endsWith("/**")) regex = regex.substring(0, regex.length - 3) + TRAILING
        regex = regex
            .replace("/**/", "/$MIDDLE")
            .replace("**", DOUBLESTAR)
            .replace("*", STAR)
            .replace("?", QUESTION)
        // Backslash first, so the escapes added for the other metacharacters are not doubled.
        for (meta in PATH_META) regex = regex.replace(meta.toString(), "\\$meta")
        return regex
            .replace(LEADING, "(.*/)?")
            .replace(TRAILING, "(/.*)?")
            .replace(MIDDLE, "(.*/)?")
            .replace(DOUBLESTAR, ".*")
            .replace(STAR, "[^/]*")
            .replace(QUESTION, "[^/]")
    }

    private fun wildcardBody(pattern: String): String =
        buildString {
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> append('\\').append(ch)
                    else -> append(ch)
                }
            }
        }

    /** Translates [glob] without anchors; brace branches are translated recursively. */
    private fun textBody(glob: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val ch = glob[i]
            val end = when (ch) {
                '{' -> matchingBrace(glob, i)
                '[' -> glob.indexOf(']', i + 1).takeIf { it > i + 1 } ?: -1
                else -> -1
            }
            when {
                ch == '*' -> {
                    sb.append(".*")
                }

                ch == '?' -> {
                    sb.append(".")
                }

                ch == '{' && end != -1 -> {
                    val branches = splitTopLevel(glob.substring(i + 1, end))
                    sb.append(branches.joinToString("|", "(?:", ")") { textBody(it) })
                }

                ch == '[' && end != -1 -> {
                    // `[!x]` is the glob spelling of a negated class; regex spells it `[^x]`.
                    val inner = glob.substring(i + 1, end)
                    sb.append('[').append(if (inner.startsWith("!")) "^" + inner.drop(1) else inner).append(']')
                }

                else -> {
                    sb.append(Regex.escape(ch.toString()))
                }
            }
            i = if (end != -1) end + 1 else i + 1
        }
        return sb.toString()
    }

    /** Index of the `}` that closes the `{` at [open], honouring nesting, or -1 when unclosed. */
    private fun matchingBrace(
        glob: String,
        open: Int,
    ): Int {
        var depth = 0
        for (idx in open until glob.length) {
            when (glob[idx]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return idx
            }
        }
        return -1
    }

    /** Splits a brace body on commas that are not inside a nested brace. */
    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        body.forEachIndexed { idx, c ->
            when {
                c == '{' -> {
                    depth++
                }

                c == '}' -> {
                    depth--
                }

                c == ',' && depth == 0 -> {
                    parts.add(body.substring(start, idx))
                    start = idx + 1
                }
            }
        }
        parts.add(body.substring(start))
        return parts
    }
}
