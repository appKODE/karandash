package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the title format rule's findings in each output language. */
internal enum class TitleFormatText(
    override val en: String,
) : LocalizedText {
    SUGGESTION(
        "Adjust title length to meet requirements.",
    ),
    EXPLANATION(
        "Title format rules ensure consistent MR naming for changelogs and automation.",
    ),
    NO_PATTERN_MATCH(
        "MR title does not match required pattern.",
    ),
    NO_TICKET(
        "MR title has no ticket reference.",
    ),
    TRAILING_PERIOD(
        "MR title must not end with a period.",
    ),
    REMOVE_PERIOD(
        "Remove the trailing period from the title.",
    ),
}
