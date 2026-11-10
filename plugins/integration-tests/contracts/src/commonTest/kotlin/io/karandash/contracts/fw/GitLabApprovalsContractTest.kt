package io.karandash.contracts.fw

import io.karandash.contracts.support.initialized
import io.karandash.platforms.gitlab.GitLabClient
import io.karandash.platforms.gitlab.GitLabPlatformService
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.pathEndsWith
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Approvals must come from `GET /merge_requests/:iid/approvals`: GitLab returns `approved_by` only there,
 * not from `GET /merge_requests/:iid`. The mock below serves real GitLab response shapes.
 */
class GitLabApprovalsContractTest {
    private val server = RecordingMockServer(
        listOf(
            pathEndsWith("/merge_requests/7/approvals", APPROVALS_JSON),
            pathEndsWith("/merge_requests/7/commits", "[]"),
            pathEndsWith("/merge_requests/7/changes", """{"changes": []}"""),
            pathEndsWith("/merge_requests/7", MR_JSON),
            pathEndsWith("/api/v4/user", """{"id": 42, "username": "alice", "name": "Alice"}"""),
        ),
    )

    private fun client() =
        GitLabClient(
            baseUrl = "https://gitlab.example.com",
            token = "t",
            httpClient = server.httpClient(),
            approveToken = "approve-t",
            responseCache = null,
        )

    @Test
    fun `fetchMergeRequest fills approvals from the approvals endpoint`() =
        runTest {
            val service = initialized(
                GitLabPlatformService(server.engine),
                "url" to "https://gitlab.example.com",
                "token" to "t",
                "approveToken" to "approve-t",
            )

            val mr = service.fetchMergeRequest("42", "7")

            assertEquals(
                listOf("alice"),
                mr.approvals.map { it.user.username },
                "approvals empty, GET .../merge_requests/7/approvals was never called " +
                    "(requests: ${server.requests().map { it.url.encodedPath }})",
            )
        }

    @Test
    fun `isApprovedByCurrentUser sees an approval listed by the approvals endpoint`() =
        runTest {
            assertTrue(
                client().isApprovedByCurrentUser("42", 7),
                "isApprovedByCurrentUser reads approved_by from GET /merge_requests/:iid instead of the approvals endpoint",
            )
        }

    private companion object {
        /** Real `GET /projects/:id/merge_requests/:iid` shape: no `approved_by` key. */
        val MR_JSON =
            """
            {
              "id": 1007, "iid": 7, "project_id": 42, "title": "feat: add login",
              "description": "Adds login", "state": "opened",
              "source_branch": "feature/login", "target_branch": "main",
              "author": {"id": 1, "username": "author", "name": "Author"},
              "draft": false, "work_in_progress": false, "labels": [], "reviewers": [],
              "merge_status": "can_be_merged", "detailed_merge_status": "mergeable", "has_conflicts": false,
              "diff_refs": {"base_sha": "aaa", "head_sha": "bbb", "start_sha": "aaa"}
            }
            """.trimIndent()

        /** Real `GET /projects/:id/merge_requests/:iid/approvals` shape. */
        val APPROVALS_JSON =
            """
            {
              "id": 1007, "iid": 7, "project_id": 42, "title": "feat: add login", "state": "opened",
              "approved": true, "approvals_required": 1, "approvals_left": 0,
              "approved_by": [{"user": {"id": 42, "username": "alice", "name": "Alice"}}]
            }
            """.trimIndent()
    }
}
