package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the approval rule's findings in each output language. */
internal enum class ApprovalText(
    override val en: String,
) : LocalizedText {
    SUGGESTION(
        "Request review from the required approvers.",
    ),
    EXPLANATION(
        "Approval requirements ensure adequate code review before merging.",
    ),
}
