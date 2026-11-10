package io.karandash.platforms.gitea

import io.karandash.models.Approval
import io.karandash.models.BlameCommit
import io.karandash.models.BlameEntry
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.providers.common.AdapterHelpers

/**
 * Adapter to convert Gitea API models to common MergeRequest model
 */
object GiteaAdapter {
    /**
     * Convert GiteaPullRequest to MergeRequest
     */
    fun toMergeRequest(
        pr: GiteaPullRequest,
        commits: List<GiteaCommit> = emptyList(),
        files: List<GiteaFile> = emptyList(),
        reviews: List<GiteaReview> = emptyList()
    ): MergeRequest =
        MergeRequest(
            id = pr.id.toString(),
            iid = pr.number,
            title = pr.title,
            description = pr.body,
            sourceBranch = pr.head.ref,
            targetBranch = pr.base.ref,
            author = toUser(pr.user),
            state = MergeRequestState.fromString(mapState(pr.state, pr.merged)),
            draft = pr.draft,
            labels = pr.labels.map { it.name },
            commits = commits.map { toCommit(it) },
            changes = files.map { toFileChange(it) },
            approvals = extractApprovals(reviews),
            reviewers = pr.requestedReviewers.map { toUser(it) },
            mergeStatus = if (pr.mergeable == true) {
                "can_be_merged"
            } else if (pr.mergeable == false) {
                "cannot_be_merged"
            } else {
                null
            },
            hasConflicts = pr.mergeable?.let { !it },
            diffRefs = DiffRefs(baseSha = pr.base.sha, headSha = pr.head.sha)
        )

    internal fun toUser(user: GiteaUser): User =
        User(
            username = user.login,
            name = user.fullName ?: user.login,
            email = user.email
        )

    private fun toCommit(commit: GiteaCommit): Commit =
        Commit(
            sha = commit.sha,
            message = commit.commit.message,
            author = CommitAuthor(
                name = commit.commit.author.name,
                email = commit.commit.author.email
            ),
            timestamp = commit.commit.author.date
        )

    private fun toFileChange(file: GiteaFile): FileChange =
        FileChange(
            path = file.filename,
            changeType = determineChangeType(file.status),
            additions = file.additions,
            deletions = file.deletions
        )

    private fun determineChangeType(status: String): ChangeType = AdapterHelpers.mapChangeType(status)

    private fun mapState(
        state: String,
        merged: Boolean
    ): String =
        when {
            merged -> "merged"

            else -> AdapterHelpers.mapPlatformState(
                state,
                mapOf(
                    "open" to "opened",
                    "closed" to "closed"
                )
            )
        }

    /**
     * Convert Gitea file commit history to blame-like entries.
     *
     * Gitea has no dedicated blame REST endpoint; per-file commit history is used as a fallback.
     * Line ranges are set to 0 since per-line attribution is unavailable.
     */
    fun toBlameEntries(commits: List<GiteaCommit>): List<BlameEntry> =
        commits.map { commit ->
            AdapterHelpers.historyBlame(
                sha = commit.sha,
                authorName = commit.commit.author.name,
                authorEmail = commit.commit.author.email,
                message = commit.commit.message,
                timestamp = commit.commit.author.date,
            )
        }

    /** Each reviewer's latest verdict (approve or request changes, by review id); counts when approved and neither stale nor dismissed (a dismissed later verdict does not revive an older one). */
    private fun extractApprovals(reviews: List<GiteaReview>): List<Approval> =
        AdapterHelpers
            .latestPerReviewer(
                reviews.filter { !it.state.equals("COMMENT", ignoreCase = true) && !it.state.equals("PENDING", ignoreCase = true) },
                reviewer = { it.user.login },
                latest = { verdicts -> verdicts.maxBy { it.id } },
            ).filter { it.state.equals("APPROVED", ignoreCase = true) && !it.stale && !it.dismissed }
            .map { review -> Approval(user = toUser(review.user), approvedAt = review.submittedAt) }
}
