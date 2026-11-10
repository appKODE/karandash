package io.karandash.providers.common

import io.karandash.models.BlameCommit
import io.karandash.models.BlameEntry
import io.karandash.models.ChangeType
import io.karandash.models.CiJobStatus
import io.karandash.models.CommitAuthor

/**
 * Shared adapter utilities used across platform adapters for mapping
 * platform-specific values to common domain model types.
 */
object AdapterHelpers {
    /**
     * Whether [body] is a summary comment the bot wrote: it opens with [marker]. A human reply that merely quotes the
     * marker further down is not, so duplicate cleanup never deletes it.
     */
    fun isSummaryComment(
        body: String?,
        marker: String,
    ): Boolean = body != null && body.trimStart().startsWith(marker)

    /**
     * Map a platform-specific file-change status string to a [ChangeType].
     *
     * Covers status strings from all supported platforms:
     * - GitHub: "added", "modified", "removed", "renamed"
     * - Gitea: "added", "modified", "removed", "deleted", "renamed", "changed"
     * - Azure DevOps: contains("add"), contains("edit"), contains("delete"), contains("rename")
     * - AWS CodeCommit / Gerrit: "A", "M", "D", "R"
     * - Bitbucket: "added", "modified", "removed", "renamed"
     */
    fun mapChangeType(status: String): ChangeType =
        when (status.lowercase()) {
            "added", "a" -> ChangeType.ADDED
            "modified", "m", "edit", "changed" -> ChangeType.MODIFIED
            "deleted", "removed", "d" -> ChangeType.DELETED
            "renamed", "r" -> ChangeType.RENAMED
            else -> ChangeType.MODIFIED
        }

    /**
     * Extract the branch name from a full Git reference path.
     *
     * Strips the standard `refs/heads/` prefix used by Azure DevOps
     * and AWS CodeCommit reference names.
     *
     * @param refName Full reference path (e.g. "refs/heads/feature-branch")
     * @return The branch name portion (e.g. "feature-branch")
     */
    fun extractBranchName(refName: String): String = refName.removePrefix("refs/heads/")

    /**
     * Parse diff string to count additions and deletions.
     *
     * Counts lines starting with '+' and '-' while excluding
     * diff header lines ('+++' and '---').
     *
     * @param diff Raw diff string
     * @return Pair of (additions, deletions)
     */
    fun parseDiffStats(diff: String): Pair<Int, Int> {
        var additions = 0
        var deletions = 0

        for (line in diff.lineSequence()) {
            when {
                line.startsWith("+") && !line.startsWith("+++") -> additions++
                line.startsWith("-") && !line.startsWith("---") -> deletions++
            }
        }

        return Pair(additions, deletions)
    }

    /**
     * Map a platform-specific MR state string to a canonical state.
     *
     * @param status The platform-specific status string
     * @param mappings A map of lowercase status strings to canonical state values
     * @return The canonical state string ("opened", "merged", "closed", etc.)
     */
    fun mapPlatformState(
        status: String,
        mappings: Map<String, String>
    ): String = mappings[status.lowercase()] ?: "closed"

    /**
     * Overall CI status from per-check [jobs]: `"failed"` if any job failed, else `"pending"` if any is not
     * finished, else `"success"`; null when there are no jobs (no CI reported for the head commit).
     */
    fun aggregateCiStatus(jobs: List<CiJobStatus>): String? {
        val states = jobs.map { it.status.lowercase() }
        return when {
            states.isEmpty() -> null
            states.any { it in CI_FAILED_STATES } -> CI_FAILED
            states.all { it in CI_SUCCESS_STATES } -> CI_SUCCESS
            else -> CI_PENDING
        }
    }

    /**
     * A file-history blame entry: one commit that touched the file, with no line range (`startLine`/`endLine` 0).
     * Platforms without a line blame API report the file's commit history this way.
     */
    fun historyBlame(
        sha: String,
        authorName: String,
        authorEmail: String,
        message: String,
        timestamp: String,
    ): BlameEntry =
        BlameEntry(
            startLine = 0,
            endLine = 0,
            commit = BlameCommit(sha, CommitAuthor(authorName, authorEmail), message, timestamp),
        )

    /** A fenced suggestion block (```` ```suggestion ````) holding [code], the syntax GitHub, Gitea and Azure Repos apply. */
    fun renderSuggestion(code: String): String = "```suggestion\n$code\n```"

    /**
     * One item per reviewer: [items] grouped by [reviewer] in first-seen order, each group reduced by [latest]
     * (default: the last item, for lists already in chronological order).
     */
    fun <T> latestPerReviewer(
        items: List<T>,
        reviewer: (T) -> String,
        latest: (List<T>) -> T = { it.last() },
    ): List<T> = items.groupBy(reviewer).values.map(latest)

    const val CI_SUCCESS = "success"
    const val CI_FAILED = "failed"
    const val CI_PENDING = "pending"

    private val CI_SUCCESS_STATES = setOf("success", "succeeded", "successful", "passed", "neutral", "skipped")
    private val CI_FAILED_STATES =
        setOf("failed", "failure", "error", "cancelled", "canceled", "timed_out", "action_required", "stopped")
}
