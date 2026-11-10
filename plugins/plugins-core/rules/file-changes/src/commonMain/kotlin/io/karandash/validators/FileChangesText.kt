package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the file changes rule's findings in each output language. */
internal enum class FileChangesText(
    override val en: String,
) : LocalizedText {
    SUGGESTION(
        "Request approval from the listed owners before merging.",
    ),
    EXPLANATION(
        "Sensitive files require explicit approval to prevent unintended changes.",
    ),
    DRAFT_BLOCKED(
        "Draft MR is blocked by file changes rule",
    ),
    REMOVE_FILE(
        "Remove this file from the MR.",
    ),
    FORBIDDEN_PATH(
        "The file matches a path this project does not allow in merge requests.",
    ),
    REQUIRED_PATHS(
        "Every MR must touch the paths listed in fileChanges.requiredPaths.",
    ),
    ADD_TESTS(
        "Add tests covering the listed lines.",
    ),
    UNCOVERED(
        "Changed lines without test coverage are more likely to regress.",
    ),
}
