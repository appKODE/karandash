package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the commit body rule's findings in each output language. */
internal enum class CommitBodyText(
    override val en: String,
) : LocalizedText {
    EXPLANATION(
        "Structured commit bodies improve review quality and release note generation.",
    ),
    NO_MATCHING_BODY(
        "No commit body in this MR contains a line matching the configured pattern.",
    ),
}
