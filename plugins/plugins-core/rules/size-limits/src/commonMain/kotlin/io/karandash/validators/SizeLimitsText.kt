package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the size limits rule's findings in each output language. */
internal enum class SizeLimitsText(
    override val en: String,
) : LocalizedText {
    EXPLANATION(
        "Smaller MRs are easier to review and less likely to introduce bugs.",
    ),
    WARN_THRESHOLD(
        "Set severities.warnThreshold to a value between 50% and 99%, e.g. 80%.",
    ),
}
