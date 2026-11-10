package io.karandash.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Known canonical state values for a merge/pull request.
 *
 * Adapters normalise platform-specific status strings (e.g. GitHub's "open",
 * Azure DevOps's "active") to these values before populating [MergeRequest.state].
 */
@Serializable
enum class MergeRequestState(
    val value: String,
) {
    /** The MR/PR is open and not yet merged. */
    @SerialName("opened")
    OPENED("opened"),

    /** The MR/PR has been closed without merging. */
    @SerialName("closed")
    CLOSED("closed"),

    /** The MR/PR has been merged into the target branch. */
    @SerialName("merged")
    MERGED("merged"),

    /** The MR/PR is locked (GitLab-specific). */
    @SerialName("locked")
    LOCKED("locked"),

    /** The state value was not recognised by Karandash. */
    @SerialName("unknown")
    UNKNOWN("unknown");

    companion object {
        private const val STATUS_OPEN = "open"

        /** Index of all enum entries by their canonical [value]. */
        private val BY_VALUE: Map<String, MergeRequestState> =
            entries.associateBy { it.value }

        /**
         * Convert a raw state string from a provider adapter to a [MergeRequestState].
         * Comparison is case-insensitive. Returns [UNKNOWN] for unrecognised values.
         */
        fun fromString(state: String): MergeRequestState {
            val normalized = state.lowercase()
            return BY_VALUE[normalized]
                ?: if (normalized == STATUS_OPEN) OPENED else UNKNOWN
        }

        /**
         * Map a platform-specific MR status to a [MergeRequestState] using built-in
         * platform mapping tables. Replaces per-adapter inline maps.
         *
         * Supported platforms: gitlab, github, bitbucket, azure-devops (alias azuredevops), gerrit, gitea, awscodecommit, jenkins.
         * Falls back to [fromString] for unknown platforms.
         */
        fun fromPlatformStatus(
            platform: String,
            status: String,
        ): MergeRequestState {
            val table = PLATFORM_MAPPINGS[platform.lowercase()] ?: return fromString(status)
            return table[status.lowercase()] ?: UNKNOWN
        }

        private val GITHUB_LIKE = mapOf(STATUS_OPEN to OPENED, CLOSED.value to CLOSED, MERGED.value to MERGED)
        private val AZURE_LIKE = mapOf("active" to OPENED, "completed" to MERGED, "abandoned" to CLOSED)

        private val PLATFORM_MAPPINGS: Map<String, Map<String, MergeRequestState>> = mapOf(
            "gitlab" to mapOf(OPENED.value to OPENED, CLOSED.value to CLOSED, MERGED.value to MERGED, LOCKED.value to LOCKED),
            "github" to GITHUB_LIKE,
            "bitbucket" to mapOf(STATUS_OPEN to OPENED, MERGED.value to MERGED, "declined" to CLOSED, "superseded" to CLOSED),
            "azure-devops" to AZURE_LIKE,
            "azuredevops" to AZURE_LIKE,
            "azure" to AZURE_LIKE,
            "gerrit" to mapOf("new" to OPENED, MERGED.value to MERGED, "abandoned" to CLOSED),
            "gitea" to GITHUB_LIKE,
            "awscodecommit" to GITHUB_LIKE,
            "aws" to GITHUB_LIKE,
            "jenkins" to GITHUB_LIKE,
        )
    }
}
