package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the description format rule's findings in each output language. */
internal enum class DescriptionFormatText(
    override val en: String,
) : LocalizedText {
    EXPLANATION(
        "Structured descriptions ensure reviewers have the context they need.",
    ),
}
