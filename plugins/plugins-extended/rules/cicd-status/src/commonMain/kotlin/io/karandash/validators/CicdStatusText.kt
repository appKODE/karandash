package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the CI/CD status rule's findings in each output language. */
internal enum class CicdStatusText(
    override val en: String,
) : LocalizedText {
    SUGGESTION(
        "Check the pipeline logs and fix failing jobs.",
    ),
    EXPLANATION(
        "Passing CI/CD pipelines prevent broken code from reaching production.",
    ),
    UNAVAILABLE(
        "CI/CD status is unavailable; unable to verify required passing pipeline",
    ),
}
