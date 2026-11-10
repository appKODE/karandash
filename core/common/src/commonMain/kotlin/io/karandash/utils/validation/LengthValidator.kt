package io.karandash.utils.validation

import io.karandash.models.finding.Finding
import io.karandash.utils.finding.FindingFactory

/**
 * Reusable min/max length validation for text fields.
 *
 * Used by TitleFormatValidator and CommitMessagesValidator to check
 * title length, commit subject length, and similar text constraints.
 */
object LengthValidator {
    /**
     * Check that [text] satisfies optional min/max length constraints.
     *
     * @param text The text to validate.
     * @param minLength Minimum length (inclusive), or null to skip.
     * @param maxLength Maximum length (inclusive), or null to skip.
     * @param ruleId The rule ID for generated findings.
     * @param fieldName Human-readable field name for messages (e.g. "MR title", "commit subject").
     * @param suggestion Fix suggestion text for generated findings.
     * @param explanation Explanation text for generated findings.
     * @return List of findings (empty if length is within bounds).
     */
    fun checkLength(
        text: String,
        minLength: Int?,
        maxLength: Int?,
        ruleId: String,
        fieldName: String = "Text",
        suggestion: String = "Adjust length to meet requirements.",
        explanation: String? = null,
    ): List<Finding> =
        buildList {
            minLength?.let { min ->
                if (text.length < min) {
                    add(
                        FindingFactory.error(
                            rule = ruleId,
                            message = "$fieldName too short (min: $min, actual: ${text.length})",
                            suggestion = suggestion,
                            explanation = explanation,
                        ),
                    )
                }
            }
            maxLength?.let { max ->
                if (text.length > max) {
                    add(
                        FindingFactory.error(
                            rule = ruleId,
                            message = "$fieldName too long (max: $max, actual: ${text.length})",
                            suggestion = suggestion,
                            explanation = explanation,
                        ),
                    )
                }
            }
        }
}
