package io.karandash.config

/**
 * What `dry-run: true` suppresses, set with `dry-run-scope:` (unset = nothing).
 *
 * - [AI] skips AI provider calls; every other rule and output runs.
 * - [ALL] is a superset of [AI]: zero AI calls and no output with external side effects
 *   (platform comments, Slack, webhook, email, auto-approve); console still prints.
 */
enum class DryRunScope(
    val value: String
) {
    ALL("all"),
    AI("ai"),
    ;

    companion object {
        fun fromString(value: String): DryRunScope =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown DryRunScope: '$value'. Must be 'all' or 'ai'.")
    }
}
