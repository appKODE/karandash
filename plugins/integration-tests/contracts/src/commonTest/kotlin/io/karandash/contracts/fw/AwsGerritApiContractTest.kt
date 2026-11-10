package io.karandash.contracts.fw

import io.karandash.contracts.support.initialized
import io.karandash.platforms.awscodecommit.AwsCodeCommitPlatformService
import io.karandash.platforms.awscodecommit.AwsCodeSuiteClient
import io.karandash.platforms.gerrit.GerritClient
import io.karandash.platforms.gerrit.GerritProviderClient
import io.karandash.testfixtures.AMZ_TARGET
import io.karandash.testfixtures.MockRoute
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.amzTarget
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The AWS and Gerrit clients must call APIs in the shape the services define.
 * - AWS: CodeCommit has no operation that lists a pull request's commits (`GetCommitsFromMergeBase` does not
 *   exist), so `AwsCodeSuiteClient.fetchCommitsBetween` walks `GetCommit` along the first-parent chain from the
 *   source commit back to the destination commit. Only real CodeCommit operations may be called.
 * - Gerrit: authenticated REST calls go to `/a/...` with HTTP Basic `base64(username:http-password)` (Gerrit
 *   REST docs, "Authentication"). Both `GerritProviderClient` and `GerritClient` do this when a username and
 *   token are set, and call the anonymous paths without `/a/` otherwise.
 */
class AwsGerritApiContractTest {
    // ── AWS CodeCommit ──

    private fun awsServer() =
        RecordingMockServer(
            listOf(
                amzTarget(
                    "CodeCommit_20150413.GetPullRequest",
                    """
                    {"pullRequest": {"pullRequestId": "7", "title": "Add key", "description": "",
                      "pullRequestStatus": "OPEN", "authorArn": "arn:aws:iam::123456789012:user/dev",
                      "creationDate": "2026-10-01T10:00:00Z", "revisionId": "rev-1",
                      "pullRequestTargets": [{"repositoryName": "demo", "sourceReference": "refs/heads/feature",
                        "destinationReference": "refs/heads/main", "sourceCommit": "$HEAD", "destinationCommit": "$BASE"}]}}
                    """.trimIndent(),
                ),
                amzTarget(
                    "CodeCommit_20150413.GetCommit",
                    """
                    {"commit": {"commitId": "$HEAD", "treeId": "t1", "parents": ["$BASE"], "message": "add key",
                      "author": {"name": "Dev", "email": "dev@example.com", "date": "1700000000 +0000"},
                      "committer": {"name": "Dev", "email": "dev@example.com", "date": "1700000000 +0000"}}}
                    """.trimIndent(),
                ),
                amzTarget("CodeCommit_20150413.GetDifferences", """{"differences": []}"""),
                amzTarget("CodeCommit_20150413.GetPullRequestApprovalStates", """{"approvals": []}"""),
                MockRoute(
                    { true },
                    """{"__type": "UnknownOperationException", "message": "Unknown operation"}""",
                    "application/x-amz-json-1.1",
                    HttpStatusCode.BadRequest,
                ),
            ),
        )

    private suspend fun fetchAws(server: RecordingMockServer) =
        runCatching {
            val service = initialized(
                AwsCodeCommitPlatformService(server.engine),
                "region" to "us-east-1",
                "accessKeyId" to "AKIDEXAMPLE",
                "secretAccessKey" to "secret"
            )
            service.fetchMergeRequest("demo", "7")
        }

    @Test
    fun `AWS fetchMergeRequest calls only real CodeCommit operations`() =
        runTest {
            val server = awsServer()
            fetchAws(server)

            val unknown = server.requests().mapNotNull { it.headers[AMZ_TARGET] }.filterNot { it in CODECOMMIT_OPERATIONS }
            assertEquals(emptyList<String>(), unknown, "AWS client called non-existent CodeCommit operations")
        }

    @Test
    fun `AWS merge request lists the pull request commits`() =
        runTest {
            val server = awsServer()
            val outcome = fetchAws(server)

            val mr = assertNotNull(outcome.getOrNull(), "AWS fetchMergeRequest failed: ${outcome.exceptionOrNull()}")
            assertEquals(listOf(HEAD), mr.commits.map { it.sha }, "AWS merge request has no commits")
        }

    // ── Gerrit ──

    private fun gerritServer() =
        RecordingMockServer(
            listOf(MockRoute({ "/changes/" in it.url.encodedPath }, ")]}'\n$GERRIT_CHANGE")),
        )

    @Test
    fun `Gerrit provider client authenticates under the a prefix with Basic credentials`() =
        runTest {
            val server = gerritServer()
            runCatching {
                GerritProviderClient(
                    GERRIT_URL,
                    server.httpClient(),
                    token = TOKEN,
                    username = USERNAME
                ).fetchChange("demo", "7")
            }

            assertGerritAuthenticated(server.requests())
        }

    @Test
    fun `Gerrit comment client authenticates under the a prefix with Basic credentials`() =
        runTest {
            val server = gerritServer()
            runCatching {
                GerritClient(
                    GERRIT_URL,
                    token = TOKEN,
                    httpClient = server.httpClient(),
                    username = USERNAME
                ).getChange("demo", "7")
            }

            assertGerritAuthenticated(server.requests())
        }

    private fun assertGerritAuthenticated(requests: List<HttpRequestData>) {
        val request = assertNotNull(requests.firstOrNull(), "Gerrit client sent no request")
        assertTrue(
            request.url.encodedPath.startsWith("/a/"),
            "authenticated Gerrit request must use the /a/ prefix, was ${request.url.encodedPath}",
        )
        val header = request.headers[HttpHeaders.Authorization].orEmpty()
        val credentials = header
            .takeIf { it.startsWith("Basic ") }
            ?.let { runCatching { Base64.decode(it.removePrefix("Basic ")).decodeToString() }.getOrNull() }
        assertTrue(
            credentials?.contains(':') == true,
            "Gerrit Authorization must be Basic base64(username:http-password), was '$header'",
        )
    }

    private companion object {
        const val HEAD = "2222222222222222222222222222222222222222"
        const val BASE = "1111111111111111111111111111111111111111"
        const val GERRIT_URL = "https://gerrit.example.com"
        const val TOKEN = "http-password"
        const val USERNAME = "gerrit-user"

        /** Read operations of the CodeCommit API (API reference, "Actions"). */
        val CODECOMMIT_OPERATIONS = setOf(
            "BatchGetCommits",
            "DescribePullRequestEvents",
            "EvaluatePullRequestApprovalRules",
            "GetApprovalRuleTemplate",
            "GetBlob",
            "GetBranch",
            "GetComment",
            "GetCommentsForComparedCommit",
            "GetCommentsForPullRequest",
            "GetCommit",
            "GetDifferences",
            "GetFile",
            "GetFolder",
            "GetMergeCommit",
            "GetMergeConflicts",
            "GetMergeOptions",
            "GetPullRequest",
            "GetPullRequestApprovalStates",
            "GetPullRequestOverrideState",
            "GetRepository",
            "ListPullRequests",
            "PostCommentForPullRequest",
            "UpdateComment",
            "UpdatePullRequestApprovalState",
            "UpdatePullRequestDescription",
            "UpdatePullRequestTitle",
        ).map { "CodeCommit_20150413.$it" }.toSet()

        val GERRIT_CHANGE =
            """
            {"id": "demo~main~I1", "_number": 7, "change_id": "I1", "subject": "Add key", "project": "demo",
             "branch": "main", "status": "NEW", "owner": {"_account_id": 1000, "name": "Dev", "username": "dev"}}
            """.trimIndent()
    }
}
