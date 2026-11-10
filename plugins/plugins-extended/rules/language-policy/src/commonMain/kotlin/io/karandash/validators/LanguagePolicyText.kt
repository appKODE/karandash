package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the language policy rule's findings in each output language. */
internal enum class LanguagePolicyText(
    override val en: String,
) : LocalizedText {
    UNKNOWN_SCRIPTS(
        "Unknown scripts are ignored and do not expand the allowed character set.",
    ),
    RANGE_FORMAT(
        "Write each range as two hex code points, e.g. '0x0400-0x04FF'.",
    ),
    INVALID_RANGES(
        "Invalid ranges are ignored and do not expand the allowed character set.",
    ),
    EXPLANATION(
        "Language policy ensures consistent character encoding across the codebase.",
    ),
}
