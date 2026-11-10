package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Texts of the labels rule's findings in each output language; `{0}` is a label name. */
internal enum class LabelsText(
    override val en: String,
) : LocalizedText {
    MISSING("Missing required label: '{0}'"),
    FORBIDDEN("Forbidden label present: '{0}'"),
    SUGGESTION(
        "Add the required label via the MR sidebar.",
    ),
    EXPLANATION(
        "Labels help categorize and route MRs through review workflows.",
    ),
}
