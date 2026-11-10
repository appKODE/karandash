package io.karandash.platforms.awscodecommit

import io.karandash.models.ChangeType
import io.karandash.models.MergeRequestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwsCodeCommitAdapterTest {
    @Test
    fun testToMergeRequest() {
        // Given
        val awsPr = AwsPullRequest(
            pullRequestId = "123",
            title = "Test PR",
            description = "Test description",
            pullRequestStatus = "OPEN",
            authorArn = "arn:aws:iam::123456789012:user/testuser",
            creationDate = "2024-01-01T00:00:00Z",
            lastActivityDate = "2024-01-02T00:00:00Z",
            pullRequestTargets = listOf(
                AwsTarget(
                    repositoryName = "test-repo",
                    sourceReference = "refs/heads/feature-branch",
                    destinationReference = "refs/heads/main",
                    sourceCommit = "abc123",
                    destinationCommit = "def456"
                )
            ),
            revisionId = "rev1"
        )

        val commits = listOf(
            AwsCommit(
                commitId = "abc123",
                message = "Test commit",
                author = AwsCommitAuthor(
                    name = "Test Author",
                    email = "test@example.com",
                    date = "2024-01-01T00:00:00Z"
                )
            )
        )

        val differences = listOf(
            AwsDifference(
                beforeBlob = null,
                afterBlob = AwsBlobMetadata(
                    blobId = "blob1",
                    path = "file1.txt",
                    mode = "100644"
                ),
                changeType = "A"
            ),
            AwsDifference(
                beforeBlob = AwsBlobMetadata(
                    blobId = "blob2",
                    path = "file2.txt",
                    mode = "100644"
                ),
                afterBlob = AwsBlobMetadata(
                    blobId = "blob3",
                    path = "file2.txt",
                    mode = "100644"
                ),
                changeType = "M"
            )
        )

        val approvals = listOf(
            AwsApproval(
                userArn = "arn:aws:iam::123456789012:user/reviewer1",
                approvalState = "APPROVE"
            )
        )

        // When
        val mergeRequest = AwsCodeSuiteAdapter.toMergeRequest(awsPr, commits, differences, approvals)

        // Then
        assertEquals("123", mergeRequest.id)
        assertEquals("Test PR", mergeRequest.title)
        assertEquals("Test description", mergeRequest.description)
        assertEquals("feature-branch", mergeRequest.sourceBranch)
        assertEquals("main", mergeRequest.targetBranch)
        assertEquals("testuser", mergeRequest.author.username)
        assertEquals(MergeRequestState.OPENED, mergeRequest.state)
        assertEquals(false, mergeRequest.draft)
        assertEquals(1, mergeRequest.commits.size)
        assertEquals("abc123", mergeRequest.commits[0].sha)
        assertEquals("Test commit", mergeRequest.commits[0].message)
        assertEquals(2, mergeRequest.changes.size)
        assertEquals("file1.txt", mergeRequest.changes[0].path)
        assertEquals(ChangeType.ADDED, mergeRequest.changes[0].changeType)
        assertEquals("file2.txt", mergeRequest.changes[1].path)
        assertEquals(ChangeType.MODIFIED, mergeRequest.changes[1].changeType)
        assertEquals(1, mergeRequest.approvals.size)
        assertEquals("reviewer1", mergeRequest.approvals[0].user.username)
    }

    @Test
    fun testExtractBranchName() {
        // Test via toMergeRequest
        val awsPr = AwsPullRequest(
            pullRequestId = "1",
            title = "Test",
            description = null,
            pullRequestStatus = "OPEN",
            authorArn = "arn:aws:iam::123456789012:user/test",
            creationDate = "2024-01-01T00:00:00Z",
            pullRequestTargets = listOf(
                AwsTarget(
                    repositoryName = "repo",
                    sourceReference = "refs/heads/feature/test-branch",
                    destinationReference = "refs/heads/main",
                    sourceCommit = "abc",
                    destinationCommit = "def"
                )
            )
        )

        val mr = AwsCodeSuiteAdapter.toMergeRequest(awsPr, emptyList(), emptyList(), emptyList())
        assertEquals("feature/test-branch", mr.sourceBranch)
        assertEquals("main", mr.targetBranch)
    }

    @Test
    fun testMapAwsStatus() {
        val openPr = AwsPullRequest(
            pullRequestId = "1",
            title = "Test",
            description = null,
            pullRequestStatus = "OPEN",
            authorArn = "arn:aws:iam::123456789012:user/test",
            creationDate = "2024-01-01T00:00:00Z",
            pullRequestTargets = listOf(
                AwsTarget(
                    repositoryName = "repo",
                    sourceReference = "refs/heads/feature",
                    destinationReference = "refs/heads/main",
                    sourceCommit = "abc",
                    destinationCommit = "def"
                )
            )
        )

        val mr = AwsCodeSuiteAdapter.toMergeRequest(openPr, emptyList(), emptyList(), emptyList())
        assertEquals(MergeRequestState.OPENED, mr.state)
    }

    @Test
    fun testChangeTypeMapping() {
        val differences = listOf(
            AwsDifference(
                beforeBlob = null,
                afterBlob = AwsBlobMetadata(path = "added.txt"),
                changeType = "A"
            ),
            AwsDifference(
                beforeBlob = AwsBlobMetadata(path = "modified.txt"),
                afterBlob = AwsBlobMetadata(path = "modified.txt"),
                changeType = "M"
            ),
            AwsDifference(
                beforeBlob = AwsBlobMetadata(path = "deleted.txt"),
                afterBlob = null,
                changeType = "D"
            )
        )

        val awsPr = AwsPullRequest(
            pullRequestId = "1",
            title = "Test",
            description = null,
            pullRequestStatus = "OPEN",
            authorArn = "arn:aws:iam::123456789012:user/test",
            creationDate = "2024-01-01T00:00:00Z",
            pullRequestTargets = listOf(
                AwsTarget(
                    repositoryName = "repo",
                    sourceReference = "refs/heads/feature",
                    destinationReference = "refs/heads/main",
                    sourceCommit = "abc",
                    destinationCommit = "def"
                )
            )
        )

        val mr = AwsCodeSuiteAdapter.toMergeRequest(awsPr, emptyList(), differences, emptyList())

        assertEquals(3, mr.changes.size)
        assertEquals(ChangeType.ADDED, mr.changes[0].changeType)
        assertEquals("added.txt", mr.changes[0].path)
        assertEquals(ChangeType.MODIFIED, mr.changes[1].changeType)
        assertEquals("modified.txt", mr.changes[1].path)
        assertEquals(ChangeType.DELETED, mr.changes[2].changeType)
        assertEquals("deleted.txt", mr.changes[2].path)
    }

    @Test
    fun testFilterApprovals() {
        val approvals = listOf(
            AwsApproval(userArn = "arn:aws:iam::123456789012:user/approver1", approvalState = "APPROVE"),
            AwsApproval(userArn = "arn:aws:iam::123456789012:user/approver2", approvalState = "REVOKE"),
            AwsApproval(userArn = "arn:aws:iam::123456789012:user/approver3", approvalState = "APPROVE")
        )

        val awsPr = AwsPullRequest(
            pullRequestId = "1",
            title = "Test",
            description = null,
            pullRequestStatus = "OPEN",
            authorArn = "arn:aws:iam::123456789012:user/test",
            creationDate = "2024-01-01T00:00:00Z",
            pullRequestTargets = listOf(
                AwsTarget(
                    repositoryName = "repo",
                    sourceReference = "refs/heads/feature",
                    destinationReference = "refs/heads/main",
                    sourceCommit = "abc",
                    destinationCommit = "def"
                )
            )
        )

        val mr = AwsCodeSuiteAdapter.toMergeRequest(awsPr, emptyList(), emptyList(), approvals)

        assertEquals(2, mr.approvals.size)
        assertTrue(mr.approvals.all { it.user.username == "approver1" || it.user.username == "approver3" })
    }
}
