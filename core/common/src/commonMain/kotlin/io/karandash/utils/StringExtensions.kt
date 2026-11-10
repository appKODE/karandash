package io.karandash.utils

/**
 * Splits a comma-separated string into a trimmed, non-empty list.
 *
 * Example: `"feat, fix, ,docs"` → `["feat", "fix", "docs"]`
 */
fun String.parseCsvList(): List<String> = split(",").map { it.trim() }.filter { it.isNotEmpty() }

private const val REGEX_METACHARACTERS = "\\^$.|?*+()[]{}"

/**
 * Builds a regex alternation from strings with each regex metacharacter backslash-escaped.
 *
 * Unlike [Regex.escape] (`\Q...\E` on the JVM) the result reads the same on every platform and stays
 * plain literals, so pattern tools (diagnosis, examples, explanations) see `feat` rather than a quoted block.
 *
 * Example: `["feat", "bug.fix"]` → `"feat|bug\\.fix"`
 */
fun List<String>.toEscapedAlternation(): String =
    joinToString("|") { item -> item.map { if (it in REGEX_METACHARACTERS) "\\$it" else "$it" }.joinToString("") }

/**
 * [take] that never splits a UTF-16 surrogate pair: a high surrogate left at the cut is dropped too,
 * so a truncated label or reason never ends in half an emoji.
 *
 * Example: `"ab😀c".takeWhole(3)` → `"ab"`
 */
fun String.takeWhole(n: Int): String = take(n).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
