package io.karandash.platforms.azuredevops

import io.karandash.models.ChangeType
import io.karandash.models.MergeRequestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AzureDevOpsAdapterTest {
    @Test
    fun testToMergeRequestBasic() {
        val adoPr = AzureDevOpsPullRequest(
            pullRequestId = 123,
            title = "Test PR",
            description = "Test description",
            status = "active",
            createdBy = AzureDevOpsUser(
                id = "user-id",
                displayName = "Test User",
                uniqueName = "testuser@example.com"
            ),
            creationDate = "2024-01-01T10:00:00Z",
            sourceRefName = "refs/heads/feature-branch",
            targetRefName = "refs/heads/main",
            isDraft = false,
            labels = listOf(
                AzureDevOpsLabel(id = "1", name = "feature", active = true)
            ),
            reviewers = emptyList()
        )

        val commits = listOf(
            AzureDevOpsCommit(
                commitId = "abc123def456",
                comment = "Test commit",
                author = AzureDevOpsCommitAuthor(
                    name = "Test User",
                    email = "test@example.com",
                    date = "2024-01-01T09:00:00Z"
                ),
                committer = AzureDevOpsCommitAuthor(
                    name = "Test User",
                    email = "test@example.com",
                    date = "2024-01-01T09:00:00Z"
                )
            )
        )

        val changes = listOf(
            AzureDevOpsChangeEntry(
                item = AzureDevOpsItem(path = "/src/newfile.txt"),
                changeType = "add"
            )
        )

        val mr = AzureDevOpsAdapter.toMergeRequest(adoPr, commits, changes)

        assertEquals("123", mr.id)
        assertEquals(123, mr.iid)
        assertEquals("Test PR", mr.title)
        assertEquals("Test description", mr.description)
        assertEquals("feature-branch", mr.sourceBranch)
        assertEquals("main", mr.targetBranch)
        assertEquals("testuser@example.com", mr.author.username)
        assertEquals("Test User", mr.author.name)
        assertEquals(MergeRequestState.OPENED, mr.state)
        assertFalse(mr.draft)
        assertEquals(1, mr.labels.size)
        assertEquals("feature", mr.labels[0])
        assertEquals(1, mr.commits.size)
        assertEquals("abc123def456", mr.commits[0].sha)
        assertEquals(1, mr.changes.size)
        assertEquals("src/newfile.txt", mr.changes[0].path) // repo-relative, like every other platform
        assertEquals(ChangeType.ADDED, mr.changes[0].changeType)
    }

    @Test
    fun testToMergeRequestWithApprovals() {
        val reviewer1 = AzureDevOpsReviewer(
            id = "reviewer-1",
            displayName = "Reviewer One",
            uniqueName = "reviewer1@example.com",
            vote = 10, // Approved
            isRequired = true
        )

        val reviewer2 = AzureDevOpsReviewer(
            id = "reviewer-2",
            displayName = "Reviewer Two",
            uniqueName = "reviewer2@example.com",
            vote = 0, // No vote
            isRequired = false
        )

        val adoPr = AzureDevOpsPullRequest(
            pullRequestId = 456,
            title = "Test PR with Approvals",
            status = "active",
            createdBy = AzureDevOpsUser(
                id = "author-id",
                displayName = "Author",
                uniqueName = "author@example.com"
            ),
            creationDate = "2024-01-01T10:00:00Z",
            sourceRefName = "refs/heads/feature",
            targetRefName = "refs/heads/main",
            reviewers = listOf(reviewer1, reviewer2)
        )

        val mr = AzureDevOpsAdapter.toMergeRequest(adoPr, emptyList(), emptyList())

        assertEquals(1, mr.approvals.size)
        assertEquals("reviewer1@example.com", mr.approvals[0].user.username)
        assertEquals(2, mr.reviewers.size)
    }

    @Test
    fun testStateMapping() {
        val createPr = { status: String ->
            AzureDevOpsPullRequest(
                pullRequestId = 1,
                title = "Test",
                status = status,
                createdBy = AzureDevOpsUser(id = "user-id", displayName = "User"),
                creationDate = "2024-01-01T10:00:00Z",
                sourceRefName = "refs/heads/feature",
                targetRefName = "refs/heads/main"
            )
        }

        assertEquals(MergeRequestState.OPENED, AzureDevOpsAdapter.toMergeRequest(createPr("active"), emptyList(), emptyList()).state)
        assertEquals(MergeRequestState.MERGED, AzureDevOpsAdapter.toMergeRequest(createPr("completed"), emptyList(), emptyList()).state)
        assertEquals(MergeRequestState.CLOSED, AzureDevOpsAdapter.toMergeRequest(createPr("abandoned"), emptyList(), emptyList()).state)
    }

    @Test
    fun testDraftDetection() {
        val draftPr = AzureDevOpsPullRequest(
            pullRequestId = 1,
            title = "Draft PR",
            status = "active",
            createdBy = AzureDevOpsUser(id = "user-id", displayName = "User"),
            creationDate = "2024-01-01T10:00:00Z",
            sourceRefName = "refs/heads/feature",
            targetRefName = "refs/heads/main",
            isDraft = true
        )

        val normalPr = AzureDevOpsPullRequest(
            pullRequestId = 2,
            title = "Normal PR",
            status = "active",
            createdBy = AzureDevOpsUser(id = "user-id", displayName = "User"),
            creationDate = "2024-01-01T10:00:00Z",
            sourceRefName = "refs/heads/feature",
            targetRefName = "refs/heads/main",
            isDraft = false
        )

        assertTrue(AzureDevOpsAdapter.toMergeRequest(draftPr, emptyList(), emptyList()).draft)
        assertFalse(AzureDevOpsAdapter.toMergeRequest(normalPr, emptyList(), emptyList()).draft)
    }

    @Test
    fun testBranchNameExtraction() {
        val pr = AzureDevOpsPullRequest(
            pullRequestId = 1,
            title = "Test",
            status = "active",
            createdBy = AzureDevOpsUser(id = "user-id", displayName = "User"),
            creationDate = "2024-01-01T10:00:00Z",
            sourceRefName = "refs/heads/feature/my-feature",
            targetRefName = "refs/heads/develop"
        )

        val mr = AzureDevOpsAdapter.toMergeRequest(pr, emptyList(), emptyList())

        assertEquals("feature/my-feature", mr.sourceBranch)
        assertEquals("develop", mr.targetBranch)
    }

    @Test
    fun testFileChangeTypes() {
        val changes = listOf(
            AzureDevOpsChangeEntry(
                item = AzureDevOpsItem(path = "/new.txt"),
                changeType = "add"
            ),
            AzureDevOpsChangeEntry(
                item = AzureDevOpsItem(path = "/existing.txt"),
                changeType = "edit"
            ),
            AzureDevOpsChangeEntry(
                item = AzureDevOpsItem(path = "/deleted.txt"),
                changeType = "delete"
            ),
            AzureDevOpsChangeEntry(
                item = AzureDevOpsItem(path = "/renamed.txt"),
                changeType = "rename"
            )
        )

        val pr = AzureDevOpsPullRequest(
            pullRequestId = 1,
            title = "Test",
            status = "active",
            createdBy = AzureDevOpsUser(id = "user-id", displayName = "User"),
            creationDate = "2024-01-01T10:00:00Z",
            sourceRefName = "refs/heads/feature",
            targetRefName = "refs/heads/main"
        )

        val mr = AzureDevOpsAdapter.toMergeRequest(pr, emptyList(), changes)

        assertEquals(4, mr.changes.size)
        assertEquals(ChangeType.ADDED, mr.changes[0].changeType)
        assertEquals(ChangeType.MODIFIED, mr.changes[1].changeType)
        assertEquals(ChangeType.DELETED, mr.changes[2].changeType)
        assertEquals(ChangeType.RENAMED, mr.changes[3].changeType)
    }

    @Test
    fun testNullItemInChangeEntry() {
        val changes = listOf(
            AzureDevOpsChangeEntry(
                item = null,
                changeType = "add"
            )
        )

        val pr = AzureDevOpsPullRequest(
            pullRequestId = 1,
            title = "Test",
            status = "active",
            createdBy = AzureDevOpsUser(id = "user-id", displayName = "User"),
            creationDate = "2024-01-01T10:00:00Z",
            sourceRefName = "refs/heads/feature",
            targetRefName = "refs/heads/main"
        )

        val mr = AzureDevOpsAdapter.toMergeRequest(pr, emptyList(), changes)

        // Should filter out null items
        assertEquals(0, mr.changes.size)
    }
}
