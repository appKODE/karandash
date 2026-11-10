package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the code owners rule's findings in each output language. */
internal enum class CodeownersText(
    override val en: String,
) : LocalizedText {
    REQUEST_REVIEW(
        "Request review from the listed code owners.",
    ),
    EXPLANATION(
        "Code owners review changes in areas they maintain.",
    ),
    ADD_FILE(
        "Add a CODEOWNERS file, point 'codeowners.path' at it, or turn the rule off.",
    ),
    NO_FILE(
        "Without a readable CODEOWNERS file the rule cannot tell who owns the changed files.",
    ),
}
