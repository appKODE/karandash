package io.karandash.plugins.ai.core

import io.karandash.models.MergeRequest

/** Prompt sections the analyzers share, so every prompt names the merge request the same way. */
object PromptSections {
    /**
     * The `## Merge Request` section: title, description, branches, then each of [extra] as a `name: value` line,
     * and a blank line.
     */
    fun mergeRequest(
        mr: MergeRequest,
        vararg extra: Pair<String, String>,
    ): String =
        buildString {
            appendLine("## Merge Request")
            appendLine("Title: ${mr.title}")
            appendLine("Description: ${mr.description ?: "(no description)"}")
            appendLine("Source Branch: ${mr.sourceBranch}")
            appendLine("Target Branch: ${mr.targetBranch}")
            extra.forEach { (name, value) -> appendLine("$name: $value") }
            appendLine()
        }
}
