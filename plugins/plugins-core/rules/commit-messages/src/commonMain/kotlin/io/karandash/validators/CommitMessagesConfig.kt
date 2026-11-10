package io.karandash.validators

import io.karandash.api.RuleConfig
import kotlinx.serialization.Serializable

/** Structured constraint on commit type token (conventional commit style). */
@Serializable
data class CommitTypeConfig(
    val allowed: List<String>? = null,
) : RuleConfig

/** Structured constraint on the commit subject (first line body). */
@Serializable
data class CommitSubjectConfig(
    val maxLength: Int? = null,
) : RuleConfig

@Serializable
data class CommitMessagesConfig(
    val format: String? = null,
    val maxLength: Int? = null,
    val minLength: Int? = null,
    val pattern: String? = null,
    val conventionalTypes: List<String>? = null,
    // Structured config - alternative to `format`/`conventionalTypes`. Ignored when `pattern` is set.
    val type: CommitTypeConfig? = null,
    val subject: CommitSubjectConfig? = null,
) : RuleConfig {
    /**
     * Returns the effective max commit line length: explicit [maxLength] takes precedence
     * over the structured [subject] max-length.
     */
    fun effectiveMaxLength(): Int? = maxLength ?: subject?.maxLength
}
