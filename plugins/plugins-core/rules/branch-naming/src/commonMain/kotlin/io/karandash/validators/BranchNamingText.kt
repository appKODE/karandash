package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the branch naming rule's findings in each output language. */
internal enum class BranchNamingText(
    override val en: String,
) : LocalizedText {
    EXPLANATION(
        "Branch naming conventions help identify work type at a glance.",
    ),
    BOTH_PATTERNS(
        "Both pattern and allowedPatterns are set; allowedPatterns is used and pattern is ignored.",
    ),
    REMOVE_PATTERN(
        "Remove pattern, or move it into allowedPatterns.",
    ),
}
