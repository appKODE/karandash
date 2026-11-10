package io.karandash.platforms.gitea

import io.karandash.models.ChangeType
import io.karandash.models.MergeRequestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GiteaAdapterTest {
    @Test
    fun testBasicPullRequestConversion() {
        val user = GiteaUser(
            id = 1,
            login = "johndoe",
            fullName = "John Doe",
            email = "john@example.com"
        )

        val pr = GiteaPullRequest(
            id = 100,
            number = 42,
            title = "Add new feature",
            body = "This PR adds a new feature",
            htmlUrl = "https://gitea.com/owner/repo/pulls/42",
            state = "open",
            user = user,
            head = GiteaBranch(ref = "feature-branch", sha = "abc123"),
            base = GiteaBranch(ref = "main", sha = "def456"),
            labels = listOf(
                GiteaLabel(id = 1, name = "enhancement", color = "00ff00")
            ),
            draft = false,
            createdAt = "2024-01-01T10:00:00Z",
            updatedAt = "2024-01-02T10:00:00Z"
        )

        val commits = listOf(
            GiteaCommit(
                sha = "abc123",
                commit = GiteaCommitInfo(
                    message = "Add feature",
                    author = GiteaCommitAuthor("John Doe", "john@example.com", "2024-01-01T10:00:00Z"),
                    committer = GiteaCommitAuthor("John Doe", "john@example.com", "2024-01-01T10:00:00Z")
                )
            )
        )

        val files = listOf(
            GiteaFile(
                filename = "src/feature.kt",
                status = "added",
                additions = 50,
                deletions = 0
            )
        )

        val mr = GiteaAdapter.toMergeRequest(pr, commits, files, emptyList())

        assertEquals("100", mr.id)
        assertEquals(42, mr.iid)
        assertEquals("Add new feature", mr.title)
        assertEquals("This PR adds a new feature", mr.description)
        assertEquals("feature-branch", mr.sourceBranch)
        assertEquals("main", mr.targetBranch)
        assertEquals("johndoe", mr.author.username)
        assertEquals("John Doe", mr.author.name)
        assertEquals(MergeRequestState.OPENED, mr.state)
        assertFalse(mr.draft)
        assertEquals(listOf("enhancement"), mr.labels)
        assertEquals(1, mr.commits.size)
        assertEquals("abc123", mr.commits[0].sha)
        assertEquals(1, mr.changes.size)
        assertEquals("src/feature.kt", mr.changes[0].path)
        assertEquals(ChangeType.ADDED, mr.changes[0].changeType)
    }

    @Test
    fun testDraftPullRequest() {
        val user = GiteaUser(id = 2, login = "janedoe", fullName = "Jane Doe")

        val pr = GiteaPullRequest(
            id = 200,
            number = 99,
            title = "WIP: Work in progress",
            body = null,
            htmlUrl = "https://gitea.com/owner/repo/pulls/99",
            state = "open",
            user = user,
            head = GiteaBranch(ref = "wip-branch", sha = "xyz789"),
            base = GiteaBranch(ref = "develop", sha = "uvw456"),
            draft = true,
            createdAt = "2024-01-03T10:00:00Z",
            updatedAt = "2024-01-03T11:00:00Z"
        )

        val mr = GiteaAdapter.toMergeRequest(pr)

        assertTrue(mr.draft)
        assertEquals(MergeRequestState.OPENED, mr.state)
    }

    @Test
    fun testMergedPullRequest() {
        val user = GiteaUser(id = 3, login = "testuser", fullName = "Test User")

        val pr = GiteaPullRequest(
            id = 300,
            number = 55,
            title = "Merged feature",
            body = "Feature completed",
            htmlUrl = "https://gitea.com/owner/repo/pulls/55",
            state = "closed",
            user = user,
            head = GiteaBranch(ref = "feature", sha = "aaa111"),
            base = GiteaBranch(ref = "main", sha = "bbb222"),
            merged = true,
            createdAt = "2024-01-04T10:00:00Z",
            updatedAt = "2024-01-05T10:00:00Z",
            mergedAt = "2024-01-05T10:00:00Z"
        )

        val mr = GiteaAdapter.toMergeRequest(pr)

        assertEquals(MergeRequestState.MERGED, mr.state)
    }

    @Test
    fun testFileChangeTypes() {
        val user = GiteaUser(id = 1, login = "user", fullName = "User")
        val pr = GiteaPullRequest(
            id = 1,
            number = 1,
            title = "Test",
            body = null,
            htmlUrl = "https://gitea.com/o/r/pulls/1",
            state = "open",
            user = user,
            head = GiteaBranch(ref = "test", sha = "abc"),
            base = GiteaBranch(ref = "main", sha = "def"),
            createdAt = "2024-01-01T10:00:00Z",
            updatedAt = "2024-01-01T10:00:00Z"
        )

        val files = listOf(
            GiteaFile(filename = "added.kt", status = "added", additions = 100, deletions = 0),
            GiteaFile(filename = "modified.kt", status = "modified", additions = 10, deletions = 5),
            GiteaFile(filename = "deleted.kt", status = "removed", additions = 0, deletions = 50),
            GiteaFile(filename = "renamed.kt", status = "renamed", additions = 5, deletions = 3, previousFilename = "old.kt")
        )

        val mr = GiteaAdapter.toMergeRequest(pr, emptyList(), files, emptyList())

        assertEquals(4, mr.changes.size)

        val added = mr.changes.find { it.path == "added.kt" }!!
        assertEquals(ChangeType.ADDED, added.changeType)
        assertEquals(100, added.additions)

        val modified = mr.changes.find { it.path == "modified.kt" }!!
        assertEquals(ChangeType.MODIFIED, modified.changeType)

        val deleted = mr.changes.find { it.path == "deleted.kt" }!!
        assertEquals(ChangeType.DELETED, deleted.changeType)

        val renamed = mr.changes.find { it.path == "renamed.kt" }!!
        assertEquals(ChangeType.RENAMED, renamed.changeType)
    }

    @Test
    fun testApprovalsExtraction() {
        val user = GiteaUser(id = 1, login = "author", fullName = "Author")
        val reviewer1 = GiteaUser(id = 2, login = "reviewer1", fullName = "Reviewer One", email = "r1@test.com")
        val reviewer2 = GiteaUser(id = 3, login = "reviewer2", fullName = "Reviewer Two", email = "r2@test.com")

        val pr = GiteaPullRequest(
            id = 1,
            number = 1,
            title = "Test",
            body = null,
            htmlUrl = "https://gitea.com/o/r/pulls/1",
            state = "open",
            user = user,
            head = GiteaBranch(ref = "test", sha = "abc"),
            base = GiteaBranch(ref = "main", sha = "def"),
            createdAt = "2024-01-01T10:00:00Z",
            updatedAt = "2024-01-01T10:00:00Z",
            requestedReviewers = listOf(reviewer1, reviewer2)
        )

        val reviews = listOf(
            GiteaReview(
                id = 1,
                user = reviewer1,
                state = "APPROVED",
                submittedAt = "2024-01-01T12:00:00Z"
            ),
            GiteaReview(
                id = 2,
                user = reviewer2,
                state = "CHANGES_REQUESTED",
                submittedAt = "2024-01-01T13:00:00Z"
            )
        )

        val mr = GiteaAdapter.toMergeRequest(pr, emptyList(), emptyList(), reviews)

        assertEquals(1, mr.approvals.size)
        assertEquals("reviewer1", mr.approvals[0].user.username)
        assertEquals("2024-01-01T12:00:00Z", mr.approvals[0].approvedAt)

        assertEquals(2, mr.reviewers.size)
        assertTrue(mr.reviewers.any { it.username == "reviewer1" })
        assertTrue(mr.reviewers.any { it.username == "reviewer2" })
    }
}
