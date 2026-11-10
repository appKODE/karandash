package io.karandash.plugins.ai.core

import io.karandash.models.MergeRequest
import io.karandash.security.TextRedactor

/**
 * Redacts potential secrets from MR data before sending to external AI APIs.
 * Creates a deep copy with sensitive content replaced.
 *
 * Delegates text-level redaction to [TextRedactor] from `core-security`.
 */
class SecretRedactor {
    private val textRedactor = TextRedactor()

    /** Redacts all known secret patterns from the given [MergeRequest], returning a sanitized copy. */
    fun redact(mr: MergeRequest): MergeRequest =
        mr.copy(
            title = textRedactor.redact(mr.title),
            sourceBranch = textRedactor.redact(mr.sourceBranch),
            targetBranch = textRedactor.redact(mr.targetBranch),
            description = mr.description?.let { textRedactor.redact(it) },
            author = mr.author.copy(
                email = mr.author.email?.let { textRedactor.redact(it) },
            ),
            changes = mr.changes.map { change ->
                if (SensitiveFileFilter.isSensitive(change.path)) {
                    // a credential file keeps its path; its diff and content never leave the host.
                    change.copy(diffContent = change.diffContent?.let { WITHHELD_DIFF }, newContent = null)
                } else {
                    change.copy(
                        diffContent = change.diffContent?.let { textRedactor.redact(it) },
                        newContent = change.newContent?.let { textRedactor.redact(it) },
                    )
                }
            },
            commits = mr.commits.map { commit ->
                commit.copy(message = textRedactor.redact(commit.message))
            },
        )

    /** Redacts secrets from a plain string using the configured patterns. */
    internal fun redactText(text: String): String = textRedactor.redact(text)

    companion object {
        /** Placeholder diff for a file [SensitiveFileFilter] flags. */
        const val WITHHELD_DIFF = "[diff of a sensitive file withheld]"

        /** Redacts secrets using the default pattern set. */
        fun redact(mr: MergeRequest): MergeRequest = SecretRedactor().redact(mr)

        /** Redacts secrets from a plain string using the default pattern set. */
        fun redactText(text: String): String = TextRedactor.redact(text)
    }
}
