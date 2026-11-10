package io.karandash.platforms.gerrit

import io.karandash.testfixtures.RecordingMockServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Gerrit adapter and client token gate. */
class GerritRegressionTest {
    private val owner = GerritAccount(accountId = 1, name = "Author", email = "a@t.com", username = "author")
    private val reviewer = GerritAccount(accountId = 2, name = "R1", email = "r1@t.com", username = "reviewer1")

    private fun vote(
        id: Int,
        value: Int,
        date: String? = "2024-01-01T12:00:00Z",
    ) = GerritApprovalInfo(accountId = id.toLong(), name = "U$id", email = "u$id@t.com", username = "user$id", value = value, date = date)

    private fun change(
        labels: Map<String, GerritLabelInfo> = emptyMap(),
        message: String? = null,
    ): GerritChange {
        val commit = message?.let {
            GerritCommitInfo(
                commit = "abc",
                parents = emptyList(),
                author = GerritGitPerson("A", "a@t.com", "2024-01-01T10:00:00Z"),
                committer = GerritGitPerson("A", "a@t.com", "2024-01-01T10:00:00Z"),
                subject = it.lines().first(),
                message = it,
            )
        }
        return GerritChange(
            id = "1",
            number = 1,
            subject = "Test",
            project = "p",
            branch = "main",
            status = "NEW",
            owner = owner,
            currentRevision = commit?.let { "abc" },
            revisions = commit?.let {
                mapOf(
                    "abc" to
                        GerritRevision(
                            kind = "REWORK",
                            number = 1,
                            created = "x",
                            uploader = owner,
                            ref = "refs/changes/01/1/1",
                            commit = it
                        )
                )
            } ?: emptyMap(),
            labels = labels,
        )
    }

    @Test
    fun `Verified plus one is not an approval`() {
        val mr = GerritAdapter.toMergeRequest(change(mapOf("Verified" to GerritLabelInfo(all = listOf(vote(3, 1))))))
        assertEquals(emptyList(), mr.approvals)
    }

    @Test
    fun `dated vote wins over the undated approved entry`() {
        val labels = mapOf("Code-Review" to GerritLabelInfo(approved = reviewer, all = listOf(vote(2, 2).copy(username = "reviewer1"))))
        val mr = GerritAdapter.toMergeRequest(change(labels))
        assertEquals(1, mr.approvals.size)
        assertEquals("2024-01-01T12:00:00Z", mr.approvals.single().approvedAt)
    }

    @Test
    fun `a negative Verified vote beats a positive one`() {
        val labels = mapOf("Verified" to GerritLabelInfo(all = listOf(vote(3, 1), vote(4, -1))))
        assertEquals("failed", GerritAdapter.toMergeRequest(change(labels)).ciStatus)
    }

    @Test
    fun `positive Verified without a negative stays success`() {
        val labels = mapOf("Verified" to GerritLabelInfo(all = listOf(vote(3, 1))))
        assertEquals("success", GerritAdapter.toMergeRequest(change(labels)).ciStatus)
    }

    @Test
    fun `description drops subject and Change-Id footer`() {
        val message = "Fix login\n\nThe session was not refreshed.\nSecond line.\n\nChange-Id: I123abc\nSigned-off-by: A <a@t.com>\n"
        val mr = GerritAdapter.toMergeRequest(change(message = message))
        assertEquals("The session was not refreshed.\nSecond line.", mr.description)
    }

    @Test
    fun `subject only commit keeps the subject without footers`() {
        val mr = GerritAdapter.toMergeRequest(change(message = "Fix login\n\nChange-Id: I123abc\n"))
        assertEquals("Fix login", mr.description)
    }

    @Test
    fun `no commit means no description`() {
        assertNull(GerritAdapter.toMergeRequest(change()).description)
    }

    @Test
    fun `a Gerrit write without username and token fails before any request`() =
        runTest {
            val server = RecordingMockServer(emptyList())
            val client = GerritClient("https://gerrit.example.com", token = "t", httpClient = server.httpClient())
            assertFailsWith<IllegalStateException> { client.postReview("7", "hello") }
            assertEquals(0, server.requests().size)
            client.close()
        }
}
