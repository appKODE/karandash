package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** The fixed texts of the draft rule's findings in each output language; `{n}` is user data. */
internal enum class DraftText(
    override val en: String,
) : LocalizedText {
    IS_DRAFT(
        "MR is marked as draft and cannot be merged",
    ),
    WIP_TITLE(
        "MR title contains WIP indicator and cannot be merged",
    ),
    MARK_READY("Mark the MR as ready."),
    REMOVE_MARKER("Remove '{0}' from the MR title."),
    EXPLANATION(
        "Draft MRs indicate work in progress and should not be merged.",
    ),
}
