package io.karandash.platforms.gitlab

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
 * Adapter to convert GitLab API models to common MergeRequest model
 */
object GitLabAdapter {
    /**
     * Convert GitLabMergeRequest to MergeRequest
     */
    fun toMergeRequest(
        gitlabMr: GitLabMergeRequest,
        commits: List<GitLabCommit>,
        diffs: List<GitLabDiff>
    ): MergeRequest =
        MergeRequest(
            id = gitlabMr.id.toString(),
            iid = gitlabMr.iid,
            title = gitlabMr.title,
            description = gitlabMr.description,
            sourceBranch = gitlabMr.sourceBranch,
            targetBranch = gitlabMr.targetBranch,
            author = toUser(gitlabMr.author),
            state = MergeRequestState.fromString(gitlabMr.state),
            draft = gitlabMr.draft || gitlabMr.workInProgress,
            labels = gitlabMr.labels,
            commits = commits.map { toCommit(it) },
            changes = diffs.map { toFileChange(it) },
            approvals = gitlabMr.approvedBy.map { toApproval(it) },
            reviewers = gitlabMr.reviewers.map { toUser(it) },
            mergeStatus = gitlabMr.detailedMergeStatus ?: gitlabMr.mergeStatus,
            hasConflicts = gitlabMr.hasConflicts,
            ciStatus = gitlabMr.headPipeline?.status,
            diffRefs = gitlabMr.diffRefs?.let {
                DiffRefs(baseSha = it.baseSha, headSha = it.headSha, startSha = it.startSha)
            }
        )

    /**
     * Convert GitLabUser to User
     */
    private fun toUser(gitlabUser: GitLabUser): User =
        User(
            username = gitlabUser.username,
            name = gitlabUser.name,
            email = gitlabUser.email
        )

    /**
     * Convert GitLabCommit to Commit
     */
    private fun toCommit(gitlabCommit: GitLabCommit): Commit =
        Commit(
            sha = gitlabCommit.id,
            message = gitlabCommit.message,
            author = CommitAuthor(
                name = gitlabCommit.authorName,
                email = gitlabCommit.authorEmail
            ),
            timestamp = gitlabCommit.createdAt
        )

    /**
     * Convert GitLabDiff to FileChange
     */
    private fun toFileChange(gitlabDiff: GitLabDiff): FileChange {
        val changeType = when {
            gitlabDiff.newFile -> ChangeType.ADDED
            gitlabDiff.deletedFile -> ChangeType.DELETED
            gitlabDiff.renamedFile -> ChangeType.RENAMED
            else -> ChangeType.MODIFIED
        }

        val (additions, deletions) = AdapterHelpers.parseDiffStats(gitlabDiff.diff)

        return FileChange(
            path = gitlabDiff.newPath,
            changeType = changeType,
            additions = additions,
            deletions = deletions,
            diffContent = gitlabDiff.diff,
            oldPath = gitlabDiff.oldPath.takeIf { gitlabDiff.renamedFile },
        )
    }

    /**
     * Convert GitLabApprover to Approval
     */
    private fun toApproval(gitlabApprover: GitLabApprover): Approval =
        Approval(
            user = toUser(gitlabApprover.user),
            approvedAt = gitlabApprover.approvedAt
        )

    /**
     * Convert GitLab blame entries to common BlameEntry model.
     * GitLab blame API returns ranges via the `lines` array - we compute line numbers from the array index.
     */
    fun toBlameEntries(gitlabEntries: List<GitLabBlameEntry>): List<BlameEntry> {
        val result = mutableListOf<BlameEntry>()
        var currentLine = 1
        for (entry in gitlabEntries) {
            val startLine = currentLine
            val endLine = currentLine + entry.lines.size - 1
            result.add(
                BlameEntry(
                    startLine = startLine,
                    endLine = endLine,
                    commit = BlameCommit(
                        sha = entry.commit.id,
                        author = CommitAuthor(
                            name = entry.commit.authorName,
                            email = entry.commit.authorEmail,
                        ),
                        message = entry.commit.message,
                        timestamp = entry.commit.authoredDate,
                    ),
                ),
            )
            currentLine = endLine + 1
        }
        return result
    }
}
