package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the merge conflict rule's findings in each output language. */
internal enum class ConflictText(
    override val en: String,
) : LocalizedText {
    HAS_CONFLICTS(
        "MR has merge conflicts that must be resolved before merging",
    ),
    REBASE(
        "Rebase or merge the target branch to resolve conflicts.",
    ),
    EXPLANATION(
        "Merge conflicts must be resolved before merging.",
    ),
}
