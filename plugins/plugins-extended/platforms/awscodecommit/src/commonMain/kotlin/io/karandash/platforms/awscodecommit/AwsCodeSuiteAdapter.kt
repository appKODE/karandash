package io.karandash.platforms.awscodecommit

import io.karandash.models.Approval
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.providers.common.AdapterHelpers

/**
 * Adapter for converting AWS CodeCommit models to karandash models
 */
object AwsCodeSuiteAdapter {
    /**
     * Convert AWS Pull Request to MergeRequest
     * @param awsPr AWS CodeCommit pull request
     * @param commits List of AWS commits
     * @param differences List of AWS differences (file changes)
     * @param approvals List of AWS approvals
     * @return MergeRequest domain model
     */
    fun toMergeRequest(
        awsPr: AwsPullRequest,
        commits: List<AwsCommit>,
        differences: List<AwsDifference>,
        approvals: List<AwsApproval>
    ): MergeRequest {
        val target = awsPr.pullRequestTargets.firstOrNull()
            ?: throw IllegalArgumentException("Pull request has no targets")

        val headSha = target.sourceCommit
        val baseSha = target.destinationCommit

        return MergeRequest(
            id = awsPr.pullRequestId,
            iid = awsPr.pullRequestId.toIntOrNull() ?: 0,
            title = awsPr.title,
            description = awsPr.description,
            sourceBranch = AdapterHelpers.extractBranchName(target.sourceReference),
            targetBranch = AdapterHelpers.extractBranchName(target.destinationReference),
            author = extractUser(awsPr.authorArn),
            state = MergeRequestState.fromString(mapAwsStatus(awsPr.pullRequestStatus)),
            draft = false, // AWS CodeCommit doesn't have draft concept
            labels = emptyList(), // AWS CodeCommit doesn't have labels
            commits = commits.map { toCommit(it) },
            changes = differences.map { toFileChange(it) },
            approvals = approvals.filter { it.approvalState == "APPROVE" }.map { toApproval(it) },
            reviewers = emptyList(), // Would need separate API call to get reviewers
            diffRefs = if (headSha != null && baseSha != null) {
                DiffRefs(baseSha = baseSha, headSha = headSha)
            } else {
                null
            }
        )
    }

    /**
     * Convert AWS Commit to Commit
     */
    private fun toCommit(awsCommit: AwsCommit): Commit {
        val author = awsCommit.author ?: awsCommit.committer
        return Commit(
            sha = awsCommit.commitId,
            message = awsCommit.message ?: "",
            author = CommitAuthor(
                name = author?.name ?: "Unknown",
                email = author?.email ?: ""
            ),
            timestamp = author?.date ?: ""
        )
    }

    /**
     * Convert AWS Difference to FileChange
     */
    private fun toFileChange(difference: AwsDifference): FileChange {
        val changeType = AdapterHelpers.mapChangeType(difference.changeType)

        val path = difference.afterBlob?.path
            ?: difference.beforeBlob?.path
            ?: "unknown"

        return FileChange(
            path = path,
            changeType = changeType,
            additions = 0, // AWS CodeCommit API doesn't provide line counts easily
            deletions = 0
        )
    }

    /**
     * Convert AWS Approval to Approval
     */
    private fun toApproval(awsApproval: AwsApproval): Approval =
        Approval(
            user = extractUser(awsApproval.userArn),
            approvedAt = null // AWS CodeCommit doesn't provide approval timestamp in this API
        )

    /**
     * Extract username from AWS ARN
     * Format: arn:aws:iam::123456789012:user/username
     */
    internal fun extractUser(arn: String): User {
        val username = arn.substringAfterLast("/").substringAfterLast(":")
        return User(
            username = username,
            name = username,
            email = null
        )
    }

    /**
     * Map AWS pull request status to standard state
     */
    private fun mapAwsStatus(status: String): String =
        AdapterHelpers.mapPlatformState(
            status,
            mapOf(
                "open" to "opened",
                "closed" to "closed",
                "merged" to "merged"
            )
        )
}
