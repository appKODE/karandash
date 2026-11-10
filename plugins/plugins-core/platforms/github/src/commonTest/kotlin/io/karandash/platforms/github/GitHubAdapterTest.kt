package io.karandash.platforms.github

import io.karandash.models.ChangeType
import io.karandash.models.MergeRequestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubAdapterTest {
    @Test
    fun testConvertPullRequestToMergeRequest() {
        val githubPR = GitHubPullRequest(
            id = 12345L,
            number = 42,
            title = "Add new feature",
            body = "This PR adds a new feature",
            htmlUrl = "https://github.com/owner/repo/pull/42",
            state = "open",
            draft = false,
            user = GitHubUser(login = "testuser", id = 1L, name = "Test User", email = "test@example.com"),
            head = GitHubRef(ref = "feature-branch", sha = "abc123", label = "owner:feature-branch"),
            base = GitHubRef(ref = "main", sha = "def456", label = "owner:main"),
            labels = listOf(GitHubLabel(name = "enhancement")),
            requestedReviewers = listOf(GitHubUser(login = "reviewer1", id = 2L)),
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-02T00:00:00Z"
        )

        val commits = listOf(
            GitHubCommit(
                sha = "commit1",
                commit = GitHubCommitDetail(
                    message = "feat: add feature",
                    author = GitHubCommitAuthor(
                        name = "Test User",
                        email = "test@example.com",
                        date = "2024-01-01T00:00:00Z"
                    )
                )
            )
        )

        val files = listOf(
            GitHubFile(
                filename = "src/main.kt",
                status = "added",
                additions = 10,
                deletions = 0
            ),
            GitHubFile(
                filename = "src/test.kt",
                status = "modified",
                additions = 5,
                deletions = 2
            )
        )

        val reviews = listOf(
            GitHubReview(
                id = 1L,
                user = GitHubUser(login = "reviewer1", id = 2L),
                state = "APPROVED",
                submittedAt = "2024-01-02T00:00:00Z"
            )
        )

        val mr = GitHubAdapter.toMergeRequest(githubPR, commits, files, reviews)

        assertEquals("12345", mr.id)
        assertEquals(42, mr.iid)
        assertEquals("Add new feature", mr.title)
        assertEquals("This PR adds a new feature", mr.description)
        assertEquals("feature-branch", mr.sourceBranch)
        assertEquals("main", mr.targetBranch)
        assertEquals("testuser", mr.author.username)
        assertEquals(MergeRequestState.OPENED, mr.state)
        assertEquals(false, mr.draft)
        assertEquals(listOf("enhancement"), mr.labels)
        assertEquals(1, mr.commits.size)
        assertEquals("commit1", mr.commits[0].sha)
        assertEquals(2, mr.changes.size)
        assertEquals(ChangeType.ADDED, mr.changes[0].changeType)
        assertEquals(ChangeType.MODIFIED, mr.changes[1].changeType)
        assertEquals(1, mr.approvals.size)
        assertEquals("reviewer1", mr.approvals[0].user.username)
        assertEquals(1, mr.reviewers.size)
    }

    @Test
    fun testConvertDraftPullRequest() {
        val githubPR = GitHubPullRequest(
            id = 99L,
            number = 1,
            title = "WIP: Draft PR",
            body = null,
            htmlUrl = "https://github.com/owner/repo/pull/1",
            state = "open",
            draft = true,
            user = GitHubUser(login = "author", id = 1L),
            head = GitHubRef(ref = "draft", sha = "abc", label = "owner:draft"),
            base = GitHubRef(ref = "main", sha = "def", label = "owner:main"),
            labels = emptyList(),
            requestedReviewers = emptyList(),
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z"
        )

        val mr = GitHubAdapter.toMergeRequest(githubPR, emptyList(), emptyList(), emptyList())

        assertEquals(true, mr.draft)
        assertNull(mr.description)
        assertTrue(mr.labels.isEmpty())
        assertTrue(mr.commits.isEmpty())
        assertTrue(mr.changes.isEmpty())
        assertTrue(mr.approvals.isEmpty())
    }

    @Test
    fun testConvertFileChangeTypes() {
        val files = listOf(
            GitHubFile(filename = "added.kt", status = "added", additions = 10, deletions = 0),
            GitHubFile(filename = "modified.kt", status = "modified", additions = 5, deletions = 3),
            GitHubFile(filename = "deleted.kt", status = "removed", additions = 0, deletions = 20),
            GitHubFile(filename = "renamed.kt", status = "renamed", additions = 0, deletions = 0),
            GitHubFile(filename = "unknown.kt", status = "unknown", additions = 1, deletions = 1)
        )

        val githubPR = createMinimalPR()
        val mr = GitHubAdapter.toMergeRequest(githubPR, emptyList(), files, emptyList())

        assertEquals(5, mr.changes.size)
        assertEquals(ChangeType.ADDED, mr.changes[0].changeType)
        assertEquals(ChangeType.MODIFIED, mr.changes[1].changeType)
        assertEquals(ChangeType.DELETED, mr.changes[2].changeType)
        assertEquals(ChangeType.RENAMED, mr.changes[3].changeType)
        assertEquals(ChangeType.MODIFIED, mr.changes[4].changeType) // unknown defaults to MODIFIED
    }

    @Test
    fun testConvertReviewsOnlyApproved() {
        val reviews = listOf(
            GitHubReview(
                id = 1L,
                user = GitHubUser(login = "user1", id = 1L),
                state = "APPROVED",
                submittedAt = "2024-01-01T00:00:00Z"
            ),
            GitHubReview(
                id = 2L,
                user = GitHubUser(login = "user2", id = 2L),
                state = "CHANGES_REQUESTED",
                submittedAt = "2024-01-02T00:00:00Z"
            ),
            GitHubReview(
                id = 3L,
                user = GitHubUser(login = "user3", id = 3L),
                state = "COMMENTED",
                submittedAt = "2024-01-03T00:00:00Z"
            )
        )

        val githubPR = createMinimalPR()
        val mr = GitHubAdapter.toMergeRequest(githubPR, emptyList(), emptyList(), reviews)

        assertEquals(1, mr.approvals.size)
        assertEquals("user1", mr.approvals[0].user.username)
        assertEquals("2024-01-01T00:00:00Z", mr.approvals[0].approvedAt)
    }

    private fun createMinimalPR() =
        GitHubPullRequest(
            id = 1L,
            number = 1,
            title = "Test",
            body = null,
            htmlUrl = "https://github.com/owner/repo/pull/1",
            state = "open",
            draft = false,
            user = GitHubUser(login = "test", id = 1L),
            head = GitHubRef(ref = "head", sha = "abc", label = "owner:head"),
            base = GitHubRef(ref = "base", sha = "def", label = "owner:base"),
            labels = emptyList(),
            requestedReviewers = emptyList(),
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z"
        )
}
