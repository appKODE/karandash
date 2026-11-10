package io.karandash.platforms.github

import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.route
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pull request's commit list stops at 250; past that the commits come from the paged compare endpoint. */
class GitHubCommitListTest {
    @Test
    fun `a pull request over 250 commits reads them from compare`() =
        runTest {
            val server = RecordingMockServer(routes(total = 300, listed = 250, compared = 300))
            val mr = fetch(server)

            assertEquals(300, mr.commits.size)
            assertEquals(300, mr.commitsTotal)
            assertFalse(mr.commitsTruncated)
            assertTrue(server.requests().any { it.url.encodedPath == "$REPO/compare/b...h1" })
        }

    @Test
    fun `a pull request within 250 commits does not call compare`() =
        runTest {
            val server = RecordingMockServer(routes(total = 2, listed = 2, compared = 0))
            val mr = fetch(server)

            assertEquals(2, mr.commits.size)
            assertFalse(mr.commitsTruncated)
            assertTrue(server.requests().none { it.url.encodedPath.contains("/compare/") })
        }

    private suspend fun fetch(server: RecordingMockServer) =
        GitHubPlatformService(server.engine).run {
            assertTrue(initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))
            fetchMergeRequest("o/r", "7")
        }

    private fun commits(n: Int) =
        (1..n).joinToString(",", "[", "]") { i ->
            """{"sha":"c$i","commit":{"message":"m$i","author":{"name":"a","email":"a@x","date":"$DATE"}}}"""
        }

    private fun routes(
        total: Int,
        listed: Int,
        compared: Int,
    ) = listOf(
        route(HttpMethod.Get, "/check-runs", """{"total_count":0,"check_runs":[]}"""),
        route(HttpMethod.Get, "/status", """{"state":"success","statuses":[]}"""),
        route(HttpMethod.Get, "$PR/commits", commits(listed)),
        route(HttpMethod.Get, "$PR/reviews", "[]"),
        route(HttpMethod.Get, "$PR/files", "[]"),
        route(HttpMethod.Get, "/compare/b...h1", """{"commits":${commits(compared)}}"""),
        route(
            HttpMethod.Get,
            PR,
            """{"id":1,"number":7,"title":"t","body":null,"html_url":"https://github.com/o/r/pull/7","state":"open",""" +
                """"draft":false,"user":{"login":"dev","id":1},"head":{"ref":"f","sha":"h1","label":"o:f"},""" +
                """"base":{"ref":"main","sha":"b","label":"o:main"},"created_at":"$DATE","updated_at":"$DATE","commits":$total}""",
        ),
    )

    private companion object {
        const val REPO = "/repos/o/r"
        const val PR = "$REPO/pulls/7"
        const val DATE = "2026-10-01T10:00:00Z"
    }
}
