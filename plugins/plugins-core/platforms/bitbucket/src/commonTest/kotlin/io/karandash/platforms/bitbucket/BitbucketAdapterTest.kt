package io.karandash.platforms.bitbucket

import io.karandash.models.ChangeType
import io.karandash.models.MergeRequestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitbucketAdapterTest {
    @Test
    fun testToMergeRequestBasic() {
        val bbPr = BitbucketPullRequest(
            id = 123,
            title = "Test PR",
            description = "Test description",
            state = "OPEN",
            author = BitbucketUser(
                uuid = "{user-uuid}",
                username = "testuser",
                displayName = "Test User",
                nickname = "testuser"
            ),
            source = BitbucketBranch(
                branch = BitbucketBranchInfo(name = "feature-branch"),
                commit = BitbucketCommitRef(hash = "abc123"),
                repository = BitbucketRepository(
                    uuid = "{repo-uuid}",
                    name = "test-repo",
                    fullName = "workspace/test-repo"
                )
            ),
            destination = BitbucketBranch(
                branch = BitbucketBranchInfo(name = "main"),
                commit = BitbucketCommitRef(hash = "def456"),
                repository = BitbucketRepository(
                    uuid = "{repo-uuid}",
                    name = "test-repo",
                    fullName = "workspace/test-repo"
                )
            ),
            createdOn = "2024-01-01T10:00:00Z",
            updatedOn = "2024-01-01T11:00:00Z",
            participants = emptyList(),
            reviewers = emptyList()
        )

        val commits = listOf(
            BitbucketCommit(
                hash = "abc123",
                date = "2024-01-01T09:00:00Z",
                message = "Test commit",
                author = BitbucketCommitAuthor(
                    raw = "Test User <test@example.com>",
                    user = null
                )
            )
        )

        val diffStats = listOf(
            BitbucketDiffStat(
                status = "added",
                oldPath = null,
                newPath = "newfile.txt",
                linesAdded = 10,
                linesRemoved = 0
            )
        )

        val mr = BitbucketAdapter.toMergeRequest(bbPr, commits, diffStats)

        assertEquals("123", mr.id)
        assertEquals(123, mr.iid)
        assertEquals("Test PR", mr.title)
        assertEquals("Test description", mr.description)
        assertEquals("feature-branch", mr.sourceBranch)
        assertEquals("main", mr.targetBranch)
        assertEquals("testuser", mr.author.username)
        assertEquals("Test User", mr.author.name)
        assertEquals(MergeRequestState.OPENED, mr.state)
        assertFalse(mr.draft)
        assertEquals(1, mr.commits.size)
        assertEquals("abc123", mr.commits[0].sha)
        assertEquals(1, mr.changes.size)
        assertEquals("newfile.txt", mr.changes[0].path)
        assertEquals(ChangeType.ADDED, mr.changes[0].changeType)
    }

    @Test
    fun testToMergeRequestWithApprovals() {
        val approver = BitbucketUser(
            uuid = "{approver-uuid}",
            username = "approver",
            displayName = "Approver User"
        )

        val bbPr = BitbucketPullRequest(
            id = 123,
            title = "Test PR",
            state = "OPEN",
            author = BitbucketUser(
                uuid = "{user-uuid}",
                displayName = "Test User"
            ),
            source = BitbucketBranch(
                branch = BitbucketBranchInfo(name = "feature"),
                commit = BitbucketCommitRef(hash = "abc"),
                repository = BitbucketRepository(
                    uuid = "{repo-uuid}",
                    name = "repo",
                    fullName = "workspace/repo"
                )
            ),
            destination = BitbucketBranch(
                branch = BitbucketBranchInfo(name = "main"),
                commit = BitbucketCommitRef(hash = "def"),
                repository = BitbucketRepository(
                    uuid = "{repo-uuid}",
                    name = "repo",
                    fullName = "workspace/repo"
                )
            ),
            createdOn = "2024-01-01T10:00:00Z",
            updatedOn = "2024-01-01T11:00:00Z",
            participants = listOf(
                BitbucketParticipant(
                    user = approver,
                    role = "REVIEWER",
                    approved = true,
                    participatedOn = "2024-01-01T12:00:00Z"
                )
            ),
            reviewers = listOf(approver)
        )

        val mr = BitbucketAdapter.toMergeRequest(bbPr, emptyList(), emptyList())

        assertEquals(1, mr.approvals.size)
        assertEquals("approver", mr.approvals[0].user.username)
        assertEquals("2024-01-01T12:00:00Z", mr.approvals[0].approvedAt)
        assertEquals(1, mr.reviewers.size)
    }

    @Test
    fun testStateMapping() {
        val createPr = { state: String ->
            BitbucketPullRequest(
                id = 1,
                title = "Test",
                state = state,
                author = BitbucketUser(uuid = "{uuid}", displayName = "User"),
                source = BitbucketBranch(
                    branch = BitbucketBranchInfo("feature"),
                    commit = BitbucketCommitRef("abc"),
                    repository = BitbucketRepository("{uuid}", "repo", "workspace/repo")
                ),
                destination = BitbucketBranch(
                    branch = BitbucketBranchInfo("main"),
                    commit = BitbucketCommitRef("def"),
                    repository = BitbucketRepository("{uuid}", "repo", "workspace/repo")
                ),
                createdOn = "2024-01-01T10:00:00Z",
                updatedOn = "2024-01-01T11:00:00Z"
            )
        }

        assertEquals(MergeRequestState.OPENED, BitbucketAdapter.toMergeRequest(createPr("OPEN"), emptyList(), emptyList()).state)
        assertEquals(MergeRequestState.OPENED, BitbucketAdapter.toMergeRequest(createPr("DRAFT"), emptyList(), emptyList()).state)
        assertEquals(MergeRequestState.MERGED, BitbucketAdapter.toMergeRequest(createPr("MERGED"), emptyList(), emptyList()).state)
        assertEquals(MergeRequestState.CLOSED, BitbucketAdapter.toMergeRequest(createPr("DECLINED"), emptyList(), emptyList()).state)
        // Bitbucket reports no conflict state, so an open PR must not claim to be mergeable
        assertEquals(null, BitbucketAdapter.toMergeRequest(createPr("OPEN"), emptyList(), emptyList()).mergeStatus)
    }

    @Test
    fun testDraftDetection() {
        val createPr = { state: String ->
            BitbucketPullRequest(
                id = 1,
                title = "Test",
                state = state,
                author = BitbucketUser(uuid = "{uuid}", displayName = "User"),
                source = BitbucketBranch(
                    branch = BitbucketBranchInfo("feature"),
                    commit = BitbucketCommitRef("abc"),
                    repository = BitbucketRepository("{uuid}", "repo", "workspace/repo")
                ),
                destination = BitbucketBranch(
                    branch = BitbucketBranchInfo("main"),
                    commit = BitbucketCommitRef("def"),
                    repository = BitbucketRepository("{uuid}", "repo", "workspace/repo")
                ),
                createdOn = "2024-01-01T10:00:00Z",
                updatedOn = "2024-01-01T11:00:00Z"
            )
        }

        assertTrue(BitbucketAdapter.toMergeRequest(createPr("DRAFT"), emptyList(), emptyList()).draft)
        assertFalse(BitbucketAdapter.toMergeRequest(createPr("OPEN"), emptyList(), emptyList()).draft)
    }

    @Test
    fun testFileChangeTypes() {
        val diffStats = listOf(
            BitbucketDiffStat(status = "added", newPath = "new.txt", linesAdded = 5, linesRemoved = 0),
            BitbucketDiffStat(status = "modified", newPath = "existing.txt", linesAdded = 3, linesRemoved = 2),
            BitbucketDiffStat(status = "removed", oldPath = "deleted.txt", linesAdded = 0, linesRemoved = 10),
            BitbucketDiffStat(status = "renamed", oldPath = "old.txt", newPath = "new.txt", linesAdded = 0, linesRemoved = 0)
        )

        val pr = BitbucketPullRequest(
            id = 1,
            title = "Test",
            state = "OPEN",
            author = BitbucketUser(uuid = "{uuid}", displayName = "User"),
            source = BitbucketBranch(
                branch = BitbucketBranchInfo("feature"),
                commit = BitbucketCommitRef("abc"),
                repository = BitbucketRepository("{uuid}", "repo", "workspace/repo")
            ),
            destination = BitbucketBranch(
                branch = BitbucketBranchInfo("main"),
                commit = BitbucketCommitRef("def"),
                repository = BitbucketRepository("{uuid}", "repo", "workspace/repo")
            ),
            createdOn = "2024-01-01T10:00:00Z",
            updatedOn = "2024-01-01T11:00:00Z"
        )

        val mr = BitbucketAdapter.toMergeRequest(pr, emptyList(), diffStats)

        assertEquals(4, mr.changes.size)
        assertEquals(ChangeType.ADDED, mr.changes[0].changeType)
        assertEquals(ChangeType.MODIFIED, mr.changes[1].changeType)
        assertEquals(ChangeType.DELETED, mr.changes[2].changeType)
        assertEquals(ChangeType.RENAMED, mr.changes[3].changeType)
    }
}
