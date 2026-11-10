package io.karandash.plugins.ai.pipeline

import io.karandash.models.finding.LocalizedText

/** Texts of the `ai.mutationTesting` and `ai.dependencies` findings in each output language. */
internal enum class ArchitectureCheckText(
    override val en: String,
) : LocalizedText {
    MUTATION_SCORE(
        "Rule mutation score {0}% ({1}/{2} caught)",
    ),
    MUTATION_ESCAPED(
        "Mutation '{0}' escaped: {1}; {2} did not fire",
    ),
    MERGE_ORDER(
        "MR '{0}' ({1}) also changes {2}: coordinate the merge order",
    ),
    DELETES_FILES(
        "Current MR deletes/renames files that MR '{0}' modifies: {1}",
    ),
}
