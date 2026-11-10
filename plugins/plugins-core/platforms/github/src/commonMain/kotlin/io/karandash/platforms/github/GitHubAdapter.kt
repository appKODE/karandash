package io.karandash.platforms.github

import io.karandash.models.Approval
import io.karandash.models.BlameCommit
import io.karandash.models.BlameEntry
import io.karandash.models.CiJobStatus
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.providers.common.AdapterHelpers

/**
 * Adapter to convert GitHub API models to common MergeRequest model
 */
object GitHubAdapter {
    /**
     * Convert GitHubPullRequest to MergeRequest
     */
    fun toMergeRequest(
        pullRequest: GitHubPullRequest,
        commits: List<GitHubCommit>,
        files: List<GitHubFile>,
        reviews: List<GitHubReview>
    ): MergeRequest =
        MergeRequest(
            id = pullRequest.id.toString(),
            iid = pullRequest.number,
            title = pullRequest.title,
            description = pullRequest.body,
            sourceBranch = pullRequest.head.ref,
            targetBranch = pullRequest.base.ref,
            author = toUser(pullRequest.user),
            state = MergeRequestState.fromString(pullRequest.state),
            draft = pullRequest.draft,
            labels = pullRequest.labels.map { it.name },
            commits = commits.map { toCommit(it) },
            changes = files.map { toFileChange(it) },
            approvals = latestVerdicts(reviews).filter { it.state == REVIEW_STATE_APPROVED }.map { toApproval(it) },
            reviewers = (pullRequest.requestedReviewers + reviews.map { it.user })
                .distinctBy { it.login }
                .filter { it.login != pullRequest.user.login }
                .map { toUser(it) },
            mergeStatus = pullRequest.mergeableState,
            hasConflicts = when {
                pullRequest.mergeableState.equals(MERGEABLE_STATE_DIRTY, ignoreCase = true) -> true
                pullRequest.mergeable != null -> pullRequest.mergeable == false
                else -> null
            },
            diffRefs = DiffRefs(baseSha = pullRequest.base.sha, headSha = pullRequest.head.sha)
        )

    /**
     * Each user's latest verdict (approved, changes requested or dismissed). GitHub keeps every review, so an
     * approval followed by a change request must not still count; comments and pending reviews carry no verdict.
     */
    private fun latestVerdicts(reviews: List<GitHubReview>): List<GitHubReview> =
        AdapterHelpers.latestPerReviewer(reviews.filter { it.state in VERDICT_STATES }, reviewer = { it.user.login })

    private fun toUser(githubUser: GitHubUser): User =
        User(
            username = githubUser.login,
            name = githubUser.name ?: githubUser.login,
            email = githubUser.email
        )

    private fun toCommit(githubCommit: GitHubCommit): Commit =
        Commit(
            sha = githubCommit.sha,
            message = githubCommit.commit.message,
            author = CommitAuthor(
                name = githubCommit.commit.author.name,
                email = githubCommit.commit.author.email
            ),
            timestamp = githubCommit.commit.author.date
        )

    private fun toFileChange(githubFile: GitHubFile): FileChange {
        val changeType = AdapterHelpers.mapChangeType(githubFile.status)

        return FileChange(
            path = githubFile.filename,
            changeType = changeType,
            additions = githubFile.additions,
            deletions = githubFile.deletions,
            diffContent = githubFile.patch
        )
    }

    private fun toApproval(review: GitHubReview): Approval =
        Approval(
            user = toUser(review.user),
            approvedAt = review.submittedAt
        )

    /**
     * Convert GitHub file commit history to blame-like entries.
     *
     * GitHub REST API has no blame endpoint, so we use per-file commit history
     * as a fallback. Line ranges are set to 0 since per-line attribution is
     * unavailable from commit history alone.
     */
    fun toBlameEntries(commits: List<GitHubCommit>): List<BlameEntry> =
        commits.map { commit ->
            AdapterHelpers.historyBlame(
                sha = commit.sha,
                authorName = commit.commit.author.name,
                authorEmail = commit.commit.author.email,
                message = commit.commit.message,
                timestamp = commit.commit.author.date,
            )
        }

    /**
     * CI jobs for the head commit: legacy commit statuses (by context) plus check runs (by name).
     * An unfinished check run reports its `status` (queued/in_progress), a finished one its `conclusion`.
     */
    fun toCiJobs(
        combined: GitHubCombinedStatus,
        checkRuns: GitHubCheckRuns,
    ): List<CiJobStatus> =
        combined.statuses.map { CiJobStatus(name = it.context, status = it.state) } +
            checkRuns.checkRuns.map { CiJobStatus(name = it.name, status = it.conclusion ?: it.status) }

    private const val REVIEW_STATE_APPROVED = "APPROVED"
    private val VERDICT_STATES = setOf(REVIEW_STATE_APPROVED, "CHANGES_REQUESTED", "DISMISSED")
    private const val MERGEABLE_STATE_DIRTY = "dirty"
}
