package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the reviewer rule's findings in each output language. */
internal enum class ReviewerText(
    override val en: String,
) : LocalizedText {
    SUGGESTION(
        "Add the listed reviewers via the MR sidebar.",
    ),
    EXPLANATION(
        "Reviewer requirements ensure proper knowledge sharing.",
    ),
}
