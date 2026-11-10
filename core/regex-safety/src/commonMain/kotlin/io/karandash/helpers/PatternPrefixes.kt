package io.karandash.helpers

/** The pattern-system prefixes, defined once for detection, prefix stripping and [RegexHelper.looksLikePatternSyntax]. */
object PatternPrefixes {
    const val NAMED = "@"
    const val POMSKY = "pomsky:"
    const val GLOB = "glob:"
    const val REGEX = "regex:"

    /** Every prefix, in detection order. */
    val ALL: List<String> = listOf(NAMED, POMSKY, GLOB, REGEX)

    /** The prefix [pattern] starts with, or null for a bare pattern. */
    fun of(pattern: String): String? = ALL.firstOrNull { pattern.startsWith(it) }

    /** [pattern] without its prefix. */
    fun strip(pattern: String): String = of(pattern)?.let { pattern.removePrefix(it) } ?: pattern
}
