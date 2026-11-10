package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the commit messages rule's findings in each output language. */
internal enum class CommitMessagesText(
    override val en: String,
) : LocalizedText {
    SUGGESTION(
        "Follow the configured commit message format.",
    ),
    EXPLANATION(
        "Consistent commit messages improve git history readability.",
    ),

    /** `{0}`: the failing commits' short SHAs. */
    NO_PATTERN_MATCH(
        "Commit message does not match required pattern: {0}",
    ),

    /** `{0}`: the failing commits' short SHAs. */
    NOT_CONVENTIONAL(
        "Commit message does not follow conventional format: {0}",
    ),
}
