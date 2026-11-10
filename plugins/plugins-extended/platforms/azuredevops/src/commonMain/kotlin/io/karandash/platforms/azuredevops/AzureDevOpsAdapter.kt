package io.karandash.platforms.azuredevops

import io.karandash.models.Approval
import io.karandash.models.BlameCommit
import io.karandash.models.BlameEntry
import io.karandash.models.ChangeType
import io.karandash.models.CiJobStatus
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.providers.common.AdapterHelpers

object AzureDevOpsAdapter {
    fun toMergeRequest(
        pr: AzureDevOpsPullRequest,
        commits: List<AzureDevOpsCommit>,
        changes: List<AzureDevOpsChangeEntry>
    ): MergeRequest =
        MergeRequest(
            id = pr.pullRequestId.toString(),
            iid = pr.pullRequestId,
            title = pr.title,
            description = pr.description,
            sourceBranch = AdapterHelpers.extractBranchName(pr.sourceRefName),
            targetBranch = AdapterHelpers.extractBranchName(pr.targetRefName),
            author = toUser(pr.createdBy),
            state = MergeRequestState.fromString(mapState(pr.status)),
            draft = pr.isDraft,
            labels = pr.labels.map { it.name },
            commits = commits.map { toCommit(it) },
            changes = changes.mapNotNull { toFileChange(it) },
            approvals = pr.reviewers
                .filter { it.vote > 0 }
                .map { toApproval(it) },
            reviewers = pr.reviewers.map { toReviewerUser(it) },
            mergeStatus = pr.mergeStatus?.let { mapMergeStatus(it) },
            hasConflicts = pr.mergeStatus?.equals("conflicts", ignoreCase = true),
            diffRefs = buildDiffRefs(pr)
        )

    private fun toUser(adoUser: AzureDevOpsUser): User =
        User(
            username = adoUser.uniqueName ?: adoUser.id,
            name = adoUser.displayName,
            email = adoUser.uniqueName
        )

    private fun toReviewerUser(reviewer: AzureDevOpsReviewer): User =
        User(
            username = reviewer.uniqueName ?: reviewer.id,
            name = reviewer.displayName,
            email = reviewer.uniqueName
        )

    private fun toCommit(adoCommit: AzureDevOpsCommit): Commit =
        Commit(
            sha = adoCommit.commitId,
            message = adoCommit.comment,
            author = CommitAuthor(
                name = adoCommit.author.name,
                email = adoCommit.author.email
            ),
            timestamp = adoCommit.author.date
        )

    private fun toFileChange(changeEntry: AzureDevOpsChangeEntry): FileChange? {
        val item = changeEntry.item ?: return null
        val path = item.path.removePrefix("/")

        val changeType = when {
            changeEntry.changeType.contains("add", ignoreCase = true) -> ChangeType.ADDED
            changeEntry.changeType.contains("delete", ignoreCase = true) -> ChangeType.DELETED
            changeEntry.changeType.contains("rename", ignoreCase = true) -> ChangeType.RENAMED
            changeEntry.changeType.contains("edit", ignoreCase = true) -> ChangeType.MODIFIED
            else -> ChangeType.MODIFIED
        }

        return FileChange(
            path = path,
            changeType = changeType,
            additions = 0, // Azure DevOps API doesn't provide line-level stats in this endpoint
            deletions = 0
        )
    }

    private fun toApproval(reviewer: AzureDevOpsReviewer): Approval =
        Approval(
            user = toReviewerUser(reviewer),
            approvedAt = null // Azure DevOps doesn't expose approval timestamp in this API
        )

    private fun buildDiffRefs(pr: AzureDevOpsPullRequest): DiffRefs? {
        val headSha = pr.lastMergeSourceCommit?.commitId ?: return null
        val baseSha = pr.lastMergeTargetCommit?.commitId ?: return null
        return DiffRefs(baseSha = baseSha, headSha = headSha)
    }

    /**
     * Convert Azure DevOps file commit history to blame-like entries.
     *
     * Azure DevOps has no blame endpoint accessible without SPA context; per-file commit
     * history is used as a fallback. Line ranges are set to 0.
     */
    fun toBlameEntries(commits: List<AzureDevOpsCommit>): List<BlameEntry> =
        commits.map { commit ->
            AdapterHelpers.historyBlame(
                sha = commit.commitId,
                authorName = commit.author.name,
                authorEmail = commit.author.email,
                message = commit.comment,
                timestamp = commit.author.date,
            )
        }

    private fun mapMergeStatus(status: String): String =
        when (status.lowercase()) {
            "succeeded" -> "can_be_merged"
            "conflicts", "rejectedbypolicy", "failure" -> "cannot_be_merged"
            else -> status
        }

    private fun mapState(adoStatus: String): String =
        AdapterHelpers.mapPlatformState(
            adoStatus,
            mapOf(
                "active" to "opened",
                "completed" to "merged",
                "abandoned" to "closed"
            )
        )

    /**
     * Pull request statuses (oldest first) as CI jobs named `genre/name`: the latest status per context wins,
     * and `notApplicable` statuses are dropped.
     */
    fun toCiJobs(statuses: List<AzureDevOpsPullRequestStatus>): List<CiJobStatus> =
        statuses
            .filterNot { it.state.equals("notApplicable", ignoreCase = true) }
            .map { status ->
                val name = listOfNotNull(status.context.genre, status.context.name).joinToString("/")
                CiJobStatus(name = name, status = status.state)
            }.associateBy { it.name }
            .values
            .toList()
}
