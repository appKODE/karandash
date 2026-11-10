package io.karandash.models

import kotlinx.serialization.Serializable

@Serializable
data class Commit(
    val sha: String,
    val message: String,
    val author: CommitAuthor,
    /**
     * ISO 8601 timestamp of the commit authoring date, e.g. "2024-03-15T10:30:00Z".
     * The exact format depends on the provider: GitLab and GitHub both return UTC ISO 8601.
     */
    val timestamp: String,
)

@Serializable
data class CommitAuthor(
    val name: String,
    val email: String,
)
