package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.utils.ConventionalTypes
import io.karandash.utils.toEscapedAlternation
import kotlinx.serialization.Serializable

/** Structured constraint on the commit/title type token. */
@Serializable
data class TitleTypeConfig(
    val allowed: List<String>? = null,
) : RuleConfig

/** Structured constraint on the scope token. */
@Serializable
data class TitleScopeConfig(
    val required: Boolean = false,
) : RuleConfig

/** Structured constraint on the subject (body) token. */
@Serializable
data class TitleSubjectConfig(
    val minLength: Int? = null,
    val noPeriod: Boolean = false,
) : RuleConfig

@Serializable
data class TitleFormatConfig(
    val pattern: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val requireTicket: Boolean = false,
    // Structured config - alternative to `pattern`. When `pattern` is set it takes precedence.
    val type: TitleTypeConfig? = null,
    val scope: TitleScopeConfig? = null,
    val subject: TitleSubjectConfig? = null,
) : RuleConfig {
    companion object {
        val DEFAULT_CONVENTIONAL_TYPES = ConventionalTypes.DEFAULT
        private const val ANY_TYPE = "[^():!\\s]+"

        /**
         * Builds a regex from structured `type`/`scope`/`subject` fields.
         * Returns `null` if no structured fields are set.
         */
        fun buildStructuredPattern(config: TitleFormatConfig): String? {
            // `scope.required` and `subject.minLength` apply without a `type` block: any type token is accepted then.
            val constrained = config.scope?.required == true || config.subject?.minLength != null
            if (config.type == null && !constrained) return null
            val typePart = config.type
                ?.let { (it.allowed ?: DEFAULT_CONVENTIONAL_TYPES).toEscapedAlternation() }
                ?: ANY_TYPE
            val scopePart = if (config.scope?.required == true) "\\(.+\\)" else "(?:\\(.+\\))?"
            val subjectPart = buildSubjectPart(config.subject)
            return "^($typePart)$scopePart!?: $subjectPart$"
        }

        private fun buildSubjectPart(subject: TitleSubjectConfig?): String {
            // A zero or negative minimum would expand to an invalid `{-1,}` quantifier.
            val minLen = subject?.minLength?.coerceAtLeast(1)
            val noPeriod = subject?.noPeriod ?: false
            return when {
                minLen != null && noPeriod -> ".{${minLen - 1},}[^.]"
                minLen != null -> ".{$minLen,}"
                noPeriod -> ".+[^.]"
                else -> ".+"
            }
        }
    }
}
