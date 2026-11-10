package io.karandash.patterns

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Casing convention for text validators that accept structured config.
 *
 * Used by branch-naming and other rule modules to specify identifier casing
 * without writing a regex.
 */
@Serializable
enum class CaseType {
    @SerialName("lower")
    LOWER,

    @SerialName("upper")
    UPPER,

    @SerialName("kebab")
    KEBAB,

    @SerialName("snake")
    SNAKE,

    @SerialName("camel")
    CAMEL,

    @SerialName("pascal")
    PASCAL,

    @SerialName("sentence")
    SENTENCE,
}

/**
 * Well-known ticket tracking system formats for structured jira-ticket config.
 *
 * Modules that support ticket validation can use this instead of raw regex.
 */
@Serializable
enum class TicketFormat {
    @SerialName("jira")
    JIRA,

    @SerialName("github")
    GITHUB,

    @SerialName("linear")
    LINEAR,

    @SerialName("shortcut")
    SHORTCUT,

    @SerialName("youtrack")
    YOUTRACK,

    @SerialName("custom")
    CUSTOM,
}

/**
 * Delimiter style used to wrap tokens in structured title/commit validators.
 */
@Serializable
enum class DelimiterType {
    @SerialName("brackets")
    BRACKETS,

    @SerialName("parens")
    PARENS,

    @SerialName("braces")
    BRACES,

    @SerialName("none")
    NONE,
}
