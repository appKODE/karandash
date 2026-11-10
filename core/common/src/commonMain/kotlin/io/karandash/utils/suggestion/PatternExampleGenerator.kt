package io.karandash.utils.suggestion

import kotlinx.serialization.Serializable

/**
 * Generates human-readable examples from regex patterns used in rule configurations.
 *
 * Pattern-based rules (title-format, commit-messages, branch-naming) use regex patterns
 * to validate MR metadata. This utility parses common regex structures to produce
 * concrete examples that help users fix violations.
 *
 * Strategy: extract alternation groups from the pattern, pick the first alternative,
 * and produce a realistic example. For patterns too complex to exemplify, returns null.
 *
 * `karandash explain` shows its example next to each pattern requirement. Rule findings use
 * `io.karandash.patterns.PatternFix` from `core/multipattern`, which also diagnoses the mismatch.
 */
object PatternExampleGenerator {
    private const val MAX_PREFIX_LENGTH = 80
    private const val MAX_HINT_LENGTH = 120
    private val QUOTE = 39.toChar()

    /**
     * Generates an example string that would match the given [pattern], or null
     * if the pattern is too complex to produce a meaningful example.
     *
     * @param pattern Regex pattern string, e.g. a conventional-commit prefix pattern
     * @return A human-readable example like "feat: ...", or null
     */
    fun generateExample(pattern: String): String? {
        if (pattern.isBlank()) return null
        val prefix = extractPrefix(pattern) ?: return null
        return if (prefix.isNotEmpty()) "$prefix ..." else null
    }

    /**
     * Generates a format hint from the pattern structure rather than a concrete example.
     * Useful as a fallback when [generateExample] returns null.
     *
     * @param pattern Regex pattern string
     * @return A format description, or null
     */
    fun generateFormatHint(pattern: String): String? {
        if (pattern.isBlank()) return null
        val cleaned = pattern
            .removePrefix("^")
            .removeSuffix("$")
        if (cleaned.length > MAX_HINT_LENGTH) return null
        return "Format: $cleaned"
    }

    /**
     * Generates a suggestion string combining example and format hint.
     * Tries [generateExample] first, falls back to [generateFormatHint].
     *
     * @param pattern Regex pattern string
     * @return Suggestion text, or null if pattern is too complex
     */
    fun suggest(pattern: String): String? {
        val example = generateExample(pattern) ?: return generateFormatHint(pattern)
        return "Example: " + QUOTE + example + QUOTE
    }

    /**
     * Generates a suggestion for glob-style patterns (used by branch-naming).
     *
     * @param patterns List of glob patterns
     * @return Suggestion text, or null if patterns are empty
     */
    fun suggestFromGlobs(patterns: List<String>): String? {
        if (patterns.isEmpty()) return null
        val first = patterns.first()
        val example = first.replace("*", "my-change").replace("?", "x")
        return "Example: " + QUOTE + example + QUOTE
    }

    private fun extractPrefix(pattern: String): String? {
        val cleaned = pattern.removePrefix("^")
        val result = StringBuilder()
        var i = 0

        while (i < cleaned.length && result.length < MAX_PREFIX_LENGTH) {
            when (val ch = cleaned[i]) {
                '(' -> {
                    val group = extractGroup(cleaned, i) ?: return finishPrefix(result)
                    val firstAlt = group.alternatives.firstOrNull()
                        ?: return finishPrefix(result)
                    result.append(firstAlt)
                    i = group.endIndex + 1
                }

                '[', '.', '*', '+', '?', '$' -> {
                    return finishPrefix(result)
                }

                '\\' -> {
                    i++
                    if (i >= cleaned.length) return finishPrefix(result)
                    i = handleEscaped(cleaned[i], result, i)
                        ?: return finishPrefix(result)
                }

                else -> {
                    result.append(ch)
                    i++
                }
            }
        }

        return finishPrefix(result)
    }

    private fun handleEscaped(
        escaped: Char,
        result: StringBuilder,
        currentIndex: Int,
    ): Int? =
        when (escaped) {
            'd' -> {
                result.append("123")
                null
            }

            'w' -> {
                result.append("word")
                null
            }

            's' -> {
                result.append(" ")
                currentIndex + 1
            }

            else -> {
                result.append(escaped)
                currentIndex + 1
            }
        }

    private fun finishPrefix(sb: StringBuilder): String? = sb.toString().takeIf { it.isNotEmpty() }

    @Serializable
    private data class GroupResult(
        val alternatives: List<String>,
        val endIndex: Int,
    )

    private fun extractGroup(
        pattern: String,
        startIndex: Int,
    ): GroupResult? {
        if (startIndex >= pattern.length || pattern[startIndex] != '(') return null

        var depth = 0
        var i = startIndex + 1
        if (i < pattern.length && pattern[i] == '?') {
            while (i < pattern.length && pattern[i] != ')' && pattern[i] != ':') i++
            if (i < pattern.length && pattern[i] == ':') i++
        }

        val alternatives = mutableListOf<String>()
        val current = StringBuilder()

        while (i < pattern.length) {
            when (pattern[i]) {
                '(' -> {
                    depth++
                    current.append(pattern[i])
                }

                ')' -> {
                    if (depth == 0) {
                        alternatives.add(current.toString())
                        return GroupResult(alternatives, i)
                    }
                    depth--
                    current.append(pattern[i])
                }

                '|' -> {
                    if (depth == 0) {
                        alternatives.add(current.toString())
                        current.clear()
                    } else {
                        current.append(pattern[i])
                    }
                }

                '\\' -> {
                    current.append(pattern[i])
                    i++
                    if (i < pattern.length) current.append(pattern[i])
                }

                else -> {
                    current.append(pattern[i])
                }
            }
            i++
        }

        return null
    }
}
