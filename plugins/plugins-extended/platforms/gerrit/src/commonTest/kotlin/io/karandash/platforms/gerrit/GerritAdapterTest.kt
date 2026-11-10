package io.karandash.platforms.gerrit

import io.karandash.models.ChangeType
import io.karandash.models.MergeRequestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GerritAdapterTest {
    @Test
    fun testBasicChangeConversion() {
        val owner = GerritAccount(
            accountId = 1000,
            name = "John Doe",
            email = "john@example.com",
            username = "johndoe"
        )

        val commitInfo = GerritCommitInfo(
            commit = "abc123",
            parents = emptyList(),
            author = GerritGitPerson("John Doe", "john@example.com", "2024-01-01T10:00:00Z"),
            committer = GerritGitPerson("John Doe", "john@example.com", "2024-01-01T10:00:00Z"),
            subject = "Fix bug",
            message = "Fix bug in authentication"
        )

        val revision = GerritRevision(
            kind = "REWORK",
            number = 1,
            created = "2024-01-01T10:00:00Z",
            uploader = owner,
            ref = "refs/changes/01/1/1",
            commit = commitInfo,
            files = mapOf(
                "src/main.kt" to GerritFileInfo(
                    status = "M",
                    linesInserted = 10,
                    linesDeleted = 5
                )
            )
        )

        val change = GerritChange(
            id = "project~master~I1234567890",
            number = 12345,
            subject = "Fix bug in authentication",
            project = "my-project",
            branch = "master",
            status = "NEW",
            owner = owner,
            currentRevision = "abc123",
            revisions = mapOf("abc123" to revision),
            hashtags = listOf("bugfix")
        )

        val mr = GerritAdapter.toMergeRequest(change)

        assertEquals("project~master~I1234567890", mr.id)
        assertEquals(12345, mr.iid)
        assertEquals("Fix bug in authentication", mr.title)
        assertEquals("Fix bug in authentication", mr.description)
        assertEquals("refs/changes/01/1/1", mr.sourceBranch)
        assertEquals("master", mr.targetBranch)
        assertEquals("johndoe", mr.author.username)
        assertEquals("John Doe", mr.author.name)
        assertEquals(MergeRequestState.OPENED, mr.state)
        assertFalse(mr.draft)
        assertEquals(listOf("bugfix"), mr.labels)
        assertEquals(1, mr.changes.size)
        assertEquals("src/main.kt", mr.changes[0].path)
        assertEquals(ChangeType.MODIFIED, mr.changes[0].changeType)
        assertEquals(10, mr.changes[0].additions)
        assertEquals(5, mr.changes[0].deletions)
    }

    @Test
    fun testWorkInProgressChange() {
        val owner = GerritAccount(
            accountId = 1000,
            name = "Jane Doe",
            email = "jane@example.com",
            username = "janedoe"
        )

        val change = GerritChange(
            id = "project~feature~I9876543210",
            number = 54321,
            subject = "WIP: New feature",
            project = "my-project",
            branch = "main",
            status = "NEW",
            owner = owner,
            workInProgress = true
        )

        val mr = GerritAdapter.toMergeRequest(change)

        assertTrue(mr.draft)
        assertEquals(MergeRequestState.OPENED, mr.state)
    }

    @Test
    fun testStatusMapping() {
        val owner = GerritAccount(
            accountId = 1000,
            name = "Test User",
            email = "test@example.com",
            username = "testuser"
        )

        val newChange = GerritChange(
            id = "1",
            number = 1,
            subject = "Test",
            project = "proj",
            branch = "main",
            status = "NEW",
            owner = owner
        )
        assertEquals(MergeRequestState.OPENED, GerritAdapter.toMergeRequest(newChange).state)

        val mergedChange = newChange.copy(status = "MERGED")
        assertEquals(MergeRequestState.MERGED, GerritAdapter.toMergeRequest(mergedChange).state)

        val abandonedChange = newChange.copy(status = "ABANDONED")
        assertEquals(MergeRequestState.CLOSED, GerritAdapter.toMergeRequest(abandonedChange).state)
    }

    @Test
    fun testFileChangeTypes() {
        val owner = GerritAccount(accountId = 1, name = "User", email = "user@test.com")

        val revision = GerritRevision(
            kind = "REWORK",
            number = 1,
            created = "2024-01-01T10:00:00Z",
            uploader = owner,
            ref = "refs/changes/01/1/1",
            files = mapOf(
                "added.kt" to GerritFileInfo(status = "A", linesInserted = 100),
                "modified.kt" to GerritFileInfo(status = "M", linesInserted = 10, linesDeleted = 5),
                "deleted.kt" to GerritFileInfo(status = "D", linesDeleted = 50),
                "renamed.kt" to GerritFileInfo(status = "R", linesInserted = 5, linesDeleted = 3)
            )
        )

        val change = GerritChange(
            id = "1",
            number = 1,
            subject = "Test",
            project = "proj",
            branch = "main",
            status = "NEW",
            owner = owner,
            currentRevision = "rev1",
            revisions = mapOf("rev1" to revision)
        )

        val mr = GerritAdapter.toMergeRequest(change)

        val addedFile = mr.changes.find { it.path == "added.kt" }!!
        assertEquals(ChangeType.ADDED, addedFile.changeType)
        assertEquals(100, addedFile.additions)
        assertEquals(0, addedFile.deletions)

        val modifiedFile = mr.changes.find { it.path == "modified.kt" }!!
        assertEquals(ChangeType.MODIFIED, modifiedFile.changeType)

        val deletedFile = mr.changes.find { it.path == "deleted.kt" }!!
        assertEquals(ChangeType.DELETED, deletedFile.changeType)

        val renamedFile = mr.changes.find { it.path == "renamed.kt" }!!
        assertEquals(ChangeType.RENAMED, renamedFile.changeType)
    }

    @Test
    fun testApprovalsExtraction() {
        val owner = GerritAccount(accountId = 1, name = "Author", email = "author@test.com", username = "author")
        val reviewer1 = GerritAccount(accountId = 2, name = "Reviewer 1", email = "r1@test.com", username = "reviewer1")

        @Suppress("UnusedPrivateProperty")
        val reviewer2 =
            GerritAccount(accountId = 3, name = "Reviewer 2", email = "r2@test.com", username = "reviewer2")

        val change = GerritChange(
            id = "1",
            number = 1,
            subject = "Test",
            project = "proj",
            branch = "main",
            status = "NEW",
            owner = owner,
            labels = mapOf(
                "Code-Review" to GerritLabelInfo(
                    approved = reviewer1,
                    all = listOf(
                        GerritApprovalInfo(
                            accountId = 2,
                            name = "Reviewer 1",
                            email = "r1@test.com",
                            username = "reviewer1",
                            value = 2,
                            date = "2024-01-01T12:00:00Z"
                        )
                    )
                ),
                "Verified" to GerritLabelInfo(
                    all = listOf(
                        GerritApprovalInfo(
                            accountId = 3,
                            name = "Reviewer 2",
                            email = "r2@test.com",
                            username = "reviewer2",
                            value = 1,
                            date = "2024-01-01T13:00:00Z"
                        )
                    )
                )
            )
        )

        val mr = GerritAdapter.toMergeRequest(change)

        // only Code-Review votes are approvals; Verified +1 is CI, not review
        assertEquals(listOf("reviewer1"), mr.approvals.map { it.user.username })
        assertEquals("success", mr.ciStatus)
    }
}
