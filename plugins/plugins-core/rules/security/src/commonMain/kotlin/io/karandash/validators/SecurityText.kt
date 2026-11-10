package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the security rule's findings in each output language. */
internal enum class SecurityText(
    override val en: String,
) : LocalizedText {
    SUGGESTION(
        "Move secrets to environment variables or a secrets vault.",
    ),
    LARGE_DIFF_SUGGESTION(
        "The diff exceeds scan.maxDiffSize; raise the limit or split the change.",
    ),
    LARGE_DIFF_EXPLANATION(
        "Diffs above the size limit are not scanned, so secrets in them would go unnoticed.",
    ),
    EXPLANATION(
        "Secrets in source control can be leaked and are hard to revoke.",
    ),
    SECRET_IN_ADDED_LINE(
        "Potential secret in an added line",
    ),
    SECRET_IN_TITLE(
        "Potential secret detected in MR title",
    ),
    SECRET_IN_DESCRIPTION(
        "Potential secret detected in MR description",
    ),
}
