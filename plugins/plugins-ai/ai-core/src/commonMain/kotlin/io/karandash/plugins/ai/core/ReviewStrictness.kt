package io.karandash.plugins.ai.core

/**
 * Controls the thoroughness of AI review analysis.
 *
 * - [LIGHT]: Fewer comments (max 3), focuses only on bugs and security issues
 * - [STANDARD]: Default balanced review
 * - [STRICT]: More comments, includes style issues, lower severity threshold
 */
enum class ReviewStrictness {
    LIGHT,
    STANDARD,
    STRICT;

    /**
     * Returns the prompt overlay instruction describing the review focus for this strictness level.
     */
    fun promptOverlay(): String? =
        when (this) {
            LIGHT -> {
                "Focus ONLY on critical bugs and security vulnerabilities. " +
                    "Ignore style, readability, and minor performance issues. " +
                    "Limit output to the most impactful findings."
            }

            STANDARD -> {
                null
            }

            STRICT -> {
                "Be thorough and strict. Include style issues, naming improvements, " +
                    "readability concerns, and minor performance suggestions in addition to " +
                    "bugs and security issues. Flag any deviation from best practices."
            }
        }

    /**
     * Returns the effective max comments for this strictness level, falling back to [configMax]
     * when the strictness level does not override it.
     */
    fun effectiveMaxComments(configMax: Int): Int =
        when (this) {
            LIGHT -> minOf(configMax, 3)
            STANDARD -> configMax
            STRICT -> maxOf(configMax, STRICT_MIN_MAX_COMMENTS)
        }

    companion object {
        private const val STRICT_MIN_MAX_COMMENTS = 20

        /**
         * Parses a strictness level from a string (`low` is [LIGHT]). Defaults to [STANDARD] for unrecognized values.
         */
        fun fromString(s: String): ReviewStrictness =
            when (s.lowercase()) {
                "light", "low" -> LIGHT
                "strict" -> STRICT
                else -> STANDARD
            }
    }
}
