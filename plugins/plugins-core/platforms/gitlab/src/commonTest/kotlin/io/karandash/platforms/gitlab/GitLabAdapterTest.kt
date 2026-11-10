package io.karandash.platforms.gitlab

import io.karandash.models.ChangeType
import io.karandash.models.MergeRequestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitLabAdapterTest {
    @Test
    fun testToMergeRequest() {
        val gitlabUser = GitLabUser(
            id = 1,
            username = "johndoe",
            name = "John Doe",
            email = "john@example.com"
        )

        val gitlabMr = GitLabMergeRequest(
            id = 12345,
            iid = 1,
            title = "Add new feature",
            description = "This adds a new feature",
            sourceBranch = "feature/new-feature",
            targetBranch = "main",
            author = gitlabUser,
            state = "opened",
            workInProgress = false,
            draft = false,
            labels = listOf("feature", "backend"),
            approvedBy = emptyList(),
            reviewers = emptyList()
        )

        val gitlabCommit = GitLabCommit(
            id = "abc123",
            short_id = "abc123",
            title = "Add feature",
            message = "Add feature implementation",
            authorName = "John Doe",
            authorEmail = "john@example.com",
            createdAt = "2024-01-01T12:00:00Z"
        )

        val gitlabDiff = GitLabDiff(
            oldPath = "file.kt",
            newPath = "file.kt",
            newFile = false,
            renamedFile = false,
            deletedFile = false,
            diff = "+added line\n-removed line"
        )

        val mergeRequest = GitLabAdapter.toMergeRequest(
            gitlabMr,
            listOf(gitlabCommit),
            listOf(gitlabDiff)
        )

        assertEquals("12345", mergeRequest.id)
        assertEquals(1, mergeRequest.iid)
        assertEquals("Add new feature", mergeRequest.title)
        assertEquals("This adds a new feature", mergeRequest.description)
        assertEquals("feature/new-feature", mergeRequest.sourceBranch)
        assertEquals("main", mergeRequest.targetBranch)
        assertEquals("johndoe", mergeRequest.author.username)
        assertEquals(MergeRequestState.OPENED, mergeRequest.state)
        assertEquals(false, mergeRequest.draft)
        assertEquals(2, mergeRequest.labels.size)
        assertEquals(1, mergeRequest.commits.size)
        assertEquals(1, mergeRequest.changes.size)
    }

    @Test
    fun testDraftDetection() {
        val gitlabUser = GitLabUser(1, "user", "User", null)

        // Test draft flag
        val draftMr = GitLabMergeRequest(
            id = 1,
            iid = 1,
            title = "Draft MR",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = gitlabUser,
            state = "opened",
            draft = true,
            workInProgress = false
        )

        val mr1 = GitLabAdapter.toMergeRequest(draftMr, emptyList(), emptyList())
        assertTrue(mr1.draft)

        // Test work_in_progress flag
        val wipMr = GitLabMergeRequest(
            id = 2,
            iid = 2,
            title = "WIP MR",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = gitlabUser,
            state = "opened",
            draft = false,
            workInProgress = true
        )

        val mr2 = GitLabAdapter.toMergeRequest(wipMr, emptyList(), emptyList())
        assertTrue(mr2.draft)
    }

    @Test
    fun testFileChangeTypes() {
        // Test added file
        val addedDiff = GitLabDiff(
            oldPath = "new.kt",
            newPath = "new.kt",
            newFile = true,
            renamedFile = false,
            deletedFile = false,
            diff = "+content"
        )

        val gitlabUser = GitLabUser(1, "user", "User", null)
        val gitlabMr = GitLabMergeRequest(
            id = 1,
            iid = 1,
            title = "Test",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = gitlabUser,
            state = "opened"
        )

        val mr = GitLabAdapter.toMergeRequest(gitlabMr, emptyList(), listOf(addedDiff))
        assertEquals(ChangeType.ADDED, mr.changes[0].changeType)

        // Test deleted file
        val deletedDiff = GitLabDiff(
            oldPath = "old.kt",
            newPath = "old.kt",
            newFile = false,
            renamedFile = false,
            deletedFile = true,
            diff = "-content"
        )

        val mr2 = GitLabAdapter.toMergeRequest(gitlabMr, emptyList(), listOf(deletedDiff))
        assertEquals(ChangeType.DELETED, mr2.changes[0].changeType)
        assertEquals(null, mr2.changes[0].oldPath)

        // Test renamed file
        val renamedDiff = GitLabDiff(
            oldPath = "old.kt",
            newPath = "new.kt",
            newFile = false,
            renamedFile = true,
            deletedFile = false,
            diff = ""
        )

        val mr3 = GitLabAdapter.toMergeRequest(gitlabMr, emptyList(), listOf(renamedDiff))
        assertEquals(ChangeType.RENAMED, mr3.changes[0].changeType)
        assertEquals("old.kt", mr3.changes[0].oldPath)

        // Test modified file
        val modifiedDiff = GitLabDiff(
            oldPath = "file.kt",
            newPath = "file.kt",
            newFile = false,
            renamedFile = false,
            deletedFile = false,
            diff = "+added\n-removed"
        )

        val mr4 = GitLabAdapter.toMergeRequest(gitlabMr, emptyList(), listOf(modifiedDiff))
        assertEquals(ChangeType.MODIFIED, mr4.changes[0].changeType)
    }

    @Test
    fun testDiffStatsParsing() {
        val diff = """
            --- a/file.kt
            +++ b/file.kt
            @@ -1,3 +1,4 @@
            -old line 1
            -old line 2
            +new line 1
            +new line 2
            +new line 3
             unchanged line
        """.trimIndent()

        val gitlabDiff = GitLabDiff(
            oldPath = "file.kt",
            newPath = "file.kt",
            newFile = false,
            renamedFile = false,
            deletedFile = false,
            diff = diff
        )

        val gitlabUser = GitLabUser(1, "user", "User", null)
        val gitlabMr = GitLabMergeRequest(
            id = 1,
            iid = 1,
            title = "Test",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = gitlabUser,
            state = "opened"
        )

        val mr = GitLabAdapter.toMergeRequest(gitlabMr, emptyList(), listOf(gitlabDiff))
        assertEquals(3, mr.changes[0].additions)
        assertEquals(2, mr.changes[0].deletions)
    }

    @Test
    fun testApprovalsConversion() {
        val approver = GitLabUser(2, "approver", "Approver", "approver@example.com")
        val gitlabApprover = GitLabApprover(
            user = approver,
            approvedAt = "2024-01-01T13:00:00Z"
        )

        val gitlabUser = GitLabUser(1, "user", "User", null)
        val gitlabMr = GitLabMergeRequest(
            id = 1,
            iid = 1,
            title = "Test",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = gitlabUser,
            state = "opened",
            approvedBy = listOf(gitlabApprover)
        )

        val mr = GitLabAdapter.toMergeRequest(gitlabMr, emptyList(), emptyList())
        assertEquals(1, mr.approvals.size)
        assertEquals("approver", mr.approvals[0].user.username)
        assertEquals("2024-01-01T13:00:00Z", mr.approvals[0].approvedAt)
    }
}
