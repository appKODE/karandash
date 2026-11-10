package io.karandash.models

import kotlinx.serialization.Serializable

/**
 * A contiguous range of lines in a file attributed to a single commit.
 *
 * Providers with native blame APIs (GitLab, Bitbucket) populate [startLine] and [endLine]
 * with real line ranges. Providers using commit-history fallback set both to 0 to indicate
 * that per-line attribution is unavailable.
 */
@Serializable
data class BlameEntry(
    val startLine: Int,
    val endLine: Int,
    val commit: BlameCommit,
)

/**
 * Commit metadata associated with a blame entry.
 *
 * @param sha Full or abbreviated commit SHA.
 * @param author The author of the commit.
 * @param message Full commit message (first line is the summary).
 * @param timestamp ISO 8601 timestamp of the commit authoring date.
 */
@Serializable
data class BlameCommit(
    val sha: String,
    val author: CommitAuthor,
    val message: String,
    val timestamp: String,
)
