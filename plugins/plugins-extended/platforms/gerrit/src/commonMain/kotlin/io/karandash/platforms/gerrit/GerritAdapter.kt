package io.karandash.platforms.gerrit

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
 * Adapter to convert Gerrit API models to common MergeRequest model
 */
object GerritAdapter {
    /**
     * Convert GerritChange to MergeRequest
     */
    fun toMergeRequest(
        change: GerritChange,
        commits: List<GerritCommitInfo> = emptyList()
    ): MergeRequest {
        val currentRevision = change.currentRevision?.let { change.revisions[it] }
        val files = currentRevision?.files?.map { (path, fileInfo) ->
            FileChange(
                path = path,
                changeType = determineChangeType(fileInfo.status),
                additions = fileInfo.linesInserted,
                deletions = fileInfo.linesDeleted
            )
        } ?: emptyList()

        val commitList = if (commits.isNotEmpty()) {
            commits.map { toCommit(it) }
        } else {
            currentRevision?.commit?.let { listOf(toCommit(it)) } ?: emptyList()
        }

        val headSha = change.currentRevision
        val baseSha = headSha?.let { sha ->
            change.revisions[sha]
                ?.commit
                ?.parents
                ?.firstOrNull()
                ?.commit
        }

        return MergeRequest(
            id = change.id,
            iid = change.number,
            title = change.subject,
            description = currentRevision?.commit?.message?.let(::descriptionOf),
            sourceBranch = currentRevision?.ref ?: "unknown",
            targetBranch = change.branch,
            author = toUser(change.owner),
            state = MergeRequestState.fromString(mapStatus(change.status)),
            draft = change.workInProgress,
            labels = change.hashtags,
            commits = commitList,
            changes = files,
            approvals = extractApprovals(change),
            reviewers = change.reviewers.reviewers.map { toUser(it) },
            mergeStatus = change.mergeable?.let { if (it) "can_be_merged" else "cannot_be_merged" },
            hasConflicts = change.mergeable?.let { !it },
            ciStatus = extractCiStatus(change),
            diffRefs = if (headSha != null && baseSha != null) {
                DiffRefs(baseSha = baseSha, headSha = headSha)
            } else {
                null
            }
        )
    }

    private fun toUser(account: GerritAccount): User =
        User(
            username = account.username ?: account.email ?: "user-${account.accountId}",
            name = account.name ?: "Unknown",
            email = account.email
        )

    private fun toCommit(commitInfo: GerritCommitInfo): Commit =
        Commit(
            sha = commitInfo.commit,
            message = commitInfo.message,
            author = CommitAuthor(
                name = commitInfo.author.name,
                email = commitInfo.author.email
            ),
            timestamp = commitInfo.author.date
        )

    private fun determineChangeType(status: String?): ChangeType =
        if (status != null) AdapterHelpers.mapChangeType(status) else ChangeType.MODIFIED

    private fun mapStatus(gerritStatus: String): String =
        AdapterHelpers.mapPlatformState(
            gerritStatus,
            mapOf(
                "new" to "opened",
                "merged" to "merged",
                "abandoned" to "closed"
            )
        )

    /**
     * Code-Review votes above zero. Other labels (Verified, custom ones) are not review approvals, and a dated
     * vote is preferred over the undated `approved` summary entry of the same account.
     */
    private fun extractApprovals(change: GerritChange): List<Approval> {
        val codeReview = change.labels[CODE_REVIEW_LABEL] ?: return emptyList()
        val voted = codeReview.all.filter { it.value != null && it.value > 0 }.map { approval ->
            Approval(
                user = toUser(GerritAccount(approval.accountId, approval.name, approval.email, approval.username)),
                approvedAt = approval.date
            )
        }
        val summary = codeReview.approved?.let { listOf(Approval(user = toUser(it), approvedAt = null)) }.orEmpty()
        return (voted + summary).distinctBy { it.user.username }
    }

    /** Commit body without the subject line and the trailing `Change-Id:`/`Signed-off-by:` trailer block. */
    private fun descriptionOf(message: String): String {
        val paragraphs = message.trim().split(PARAGRAPH_BREAK)
        val body = if (paragraphs.size > 1 && paragraphs.last().lines().all { TRAILER.matches(it) }) paragraphs.dropLast(1) else paragraphs
        val text = body.drop(1).joinToString("\n\n").trim()
        return text.ifEmpty { body.first().trim() }
    }

    private fun extractCiStatus(change: GerritChange): String? {
        val verified = change.labels["Verified"] ?: return null
        // negative first: one -1 blocks the change however many +1 votes it has
        return when {
            verified.rejected != null -> "failed"
            verified.all.any { (it.value ?: 0) < 0 } -> "failed"
            verified.approved != null -> "success"
            verified.all.any { (it.value ?: 0) > 0 } -> "success"
            else -> "pending"
        }
    }

    private const val CODE_REVIEW_LABEL = "Code-Review"
    private val PARAGRAPH_BREAK = Regex("\n\\s*\n")
    private val TRAILER = Regex("[A-Za-z][A-Za-z0-9-]*: .*")
}
