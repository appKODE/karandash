package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the context tag rule's findings in each output language. */
internal enum class ContextTagText(
    override val en: String,
) : LocalizedText {
    EXPLANATION(
        "Context tags categorize changes for routing and reporting.",
    ),
}
