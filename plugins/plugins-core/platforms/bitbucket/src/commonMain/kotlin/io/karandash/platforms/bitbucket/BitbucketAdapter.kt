package io.karandash.platforms.bitbucket

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

object BitbucketAdapter {
    private const val UNKNOWN = "unknown"

    fun toMergeRequest(
        pr: BitbucketPullRequest,
        commits: List<BitbucketCommit>,
        diffStats: List<BitbucketDiffStat>
    ): MergeRequest =
        MergeRequest(
            id = pr.id.toString(),
            iid = pr.id,
            title = pr.title,
            description = pr.description,
            sourceBranch = pr.source.branch.name,
            targetBranch = pr.destination.branch.name,
            author = toUser(pr.author),
            state = MergeRequestState.fromString(mapState(pr.state)),
            draft = pr.draft || pr.state == "DRAFT",
            labels = emptyList(), // Bitbucket doesn't have labels on PRs
            commits = commits.map { toCommit(it) },
            changes = diffStats.map { toFileChange(it) },
            approvals = pr.participants
                .filter { it.approved }
                .map { toApproval(it) },
            reviewers = pr.reviewers.map { toUser(it) },
            // Bitbucket Cloud's PR API reports no conflict state (MergeRequestField.CONFLICTS is unavailable)
            mergeStatus = null,
            diffRefs = DiffRefs(
                baseSha = pr.destination.commit.hash,
                headSha = pr.source.commit.hash
            )
        )

    private fun toUser(bbUser: BitbucketUser): User =
        User(
            username = bbUser.username ?: bbUser.nickname ?: bbUser.displayName,
            name = bbUser.displayName,
            email = null // Bitbucket API doesn't expose email in PR responses
        )

    private fun toCommit(bbCommit: BitbucketCommit): Commit {
        // Parse email from raw author string (format: "Name <email>")
        val authorEmail = bbCommit.author.raw
            .substringAfter("<", "")
            .substringBefore(">", "")
            .takeIf { it.isNotEmpty() } ?: UNKNOWN

        val authorName = bbCommit.author.raw
            .substringBefore("<")
            .trim()
            .takeIf { it.isNotEmpty() } ?: UNKNOWN

        return Commit(
            sha = bbCommit.hash,
            message = bbCommit.message,
            author = CommitAuthor(
                name = authorName,
                email = authorEmail
            ),
            timestamp = bbCommit.date
        )
    }

    private fun toFileChange(diffStat: BitbucketDiffStat): FileChange {
        val path = diffStat.newPath ?: diffStat.oldPath ?: UNKNOWN
        val changeType = AdapterHelpers.mapChangeType(diffStat.status)

        return FileChange(
            path = path,
            changeType = changeType,
            additions = diffStat.linesAdded,
            deletions = diffStat.linesRemoved
        )
    }

    private fun toApproval(participant: BitbucketParticipant): Approval =
        Approval(
            user = toUser(participant.user),
            approvedAt = participant.participatedOn
        )

    /** The commit a file-history entry already carries, or null when Bitbucket sent only the hash. */
    fun toCommit(entry: BitbucketFileHistoryCommit): BitbucketCommit? {
        val date = entry.date ?: return null
        val message = entry.message ?: return null
        val author = entry.author ?: return null
        return BitbucketCommit(hash = entry.hash, date = date, message = message, author = author)
    }

    /**
     * Convert Bitbucket file commit history to blame-like entries.
     *
     * Bitbucket Cloud has no dedicated blame API. File history is used as a fallback;
     * line ranges are set to 0 since per-line attribution is unavailable.
     */
    fun toBlameEntries(commits: List<BitbucketCommit>): List<BlameEntry> =
        commits.map { commit ->
            val authorEmail = commit.author.raw
                .substringAfter("<", "")
                .substringBefore(">", "")
                .takeIf { it.isNotEmpty() } ?: UNKNOWN
            val authorName = commit.author.raw
                .substringBefore("<")
                .trim()
                .takeIf { it.isNotEmpty() } ?: UNKNOWN
            AdapterHelpers.historyBlame(commit.hash, authorName, authorEmail, commit.message, commit.date)
        }

    private fun mapState(bbState: String): String =
        AdapterHelpers.mapPlatformState(
            bbState,
            mapOf(
                "open" to "opened",
                "draft" to "opened",
                "merged" to "merged",
                "declined" to "closed",
                "superseded" to "closed"
            )
        )

    /** Build statuses as CI jobs, one per status key (the first listed entry for a key wins). */
    fun toCiJobs(statuses: List<BitbucketCommitStatus>): List<CiJobStatus> =
        statuses.distinctBy { it.key }.map { CiJobStatus(name = it.name ?: it.key, status = it.state) }
}
