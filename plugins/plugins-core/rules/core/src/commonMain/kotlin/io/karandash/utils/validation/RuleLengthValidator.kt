package io.karandash.utils.validation

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity

/**
 * Rule-side length check shared by core rules: [LengthValidator] from `core/common` with a configurable
 * severity for the "too long" finding ("too short" stays an error).
 */
object RuleLengthValidator {
    /**
     * Checks [text] against [minLength] and [maxLength]; see [LengthValidator.checkLength] for the shared parameters.
     *
     * @param maxSeverity Severity of the "too long" finding.
     */
    fun checkLength(
        text: String,
        minLength: Int?,
        maxLength: Int?,
        ruleId: String,
        fieldName: String,
        suggestion: String,
        explanation: String?,
        maxSeverity: Severity,
    ): List<Finding> =
        LengthValidator.checkLength(text, minLength, null, ruleId, fieldName, suggestion, explanation) +
            LengthValidator
                .checkLength(text, null, maxLength, ruleId, fieldName, suggestion, explanation)
                .map { it.copy(severity = maxSeverity) }
}
