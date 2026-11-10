package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.patterns.DelimiterType
import kotlinx.serialization.Serializable

@Serializable
data class ContextTagConfig(
    val allowed: List<String> = emptyList(),
    val exactlyOne: Boolean = true,
    val checkMrTitle: Boolean = true,
    val checkCommitTitles: Boolean = true,
    val tagPattern: String? = null,
    val caseSensitive: Boolean = false,
    val delimiter: DelimiterType? = null,
) : RuleConfig {
    /**
     * Returns the effective tag regex pattern.
     *
     * Priority: explicit [tagPattern] > [delimiter]-generated > default brackets.
     */
    fun effectiveTagPattern(): String = tagPattern ?: delimiterToPattern(delimiter ?: DelimiterType.BRACKETS)

    /** Renders [tag] in the configured [delimiter] (brackets by default, also for a custom [tagPattern]). */
    fun formatTag(tag: String): String =
        when (delimiter ?: DelimiterType.BRACKETS) {
            DelimiterType.BRACKETS -> "[$tag]"
            DelimiterType.PARENS -> "($tag)"
            DelimiterType.BRACES -> "{$tag}"
            DelimiterType.NONE -> tag
        }

    companion object {
        private const val TAG_INNER = "([a-zA-Z][a-zA-Z0-9_]*)"

        private fun delimiterToPattern(type: DelimiterType): String =
            when (type) {
                DelimiterType.BRACKETS -> "\\[$TAG_INNER\\]"
                DelimiterType.PARENS -> "\\($TAG_INNER\\)"
                DelimiterType.BRACES -> "\\{$TAG_INNER\\}"
                DelimiterType.NONE -> TAG_INNER
            }
    }
}
