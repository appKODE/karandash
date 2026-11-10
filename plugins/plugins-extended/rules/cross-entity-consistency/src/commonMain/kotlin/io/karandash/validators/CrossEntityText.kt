package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the cross-entity consistency rule's findings in each output language. */
internal enum class CrossEntityText(
    override val en: String,
) : LocalizedText {
    EXPLANATION(
        "Consistent task references ensure traceability across MR components.",
    ),
    RELEASE_NOTE(
        "Each ticket in the MR scope should produce a release-note entry.",
    ),
    ONCE(
        "Each task ID should appear in CHANGELOG only once across all commits.",
    ),
    DUPLICATE(
        "Duplicate CHANGELOG entries for the same task ID indicate redundant or conflicting changelog notes.",
    ),
}
