package io.karandash.validators

import io.karandash.models.finding.LocalizedText

/** Fixed texts of the dependencies rule's findings in each output language. */
internal enum class DependencyText(
    override val en: String,
) : LocalizedText {
    PIN(
        "Pin to an exact version to ensure reproducible builds.",
    ),
    UNPINNED(
        "Unpinned versions can introduce unexpected changes when dependencies are updated.",
    ),
    OLD(
        "Older versions may contain known vulnerabilities or lack required features.",
    ),
    REMOVE(
        "Remove or replace this dependency.",
    ),
    BLOCKED(
        "This dependency version matches a configured blocked pattern.",
    ),
}
