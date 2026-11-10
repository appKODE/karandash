package io.karandash.platforms.gitlab

import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GitLabPipelineJobsTest {
    private fun client(
        status: HttpStatusCode,
        body: String,
        paths: MutableList<String>,
    ): GitLabClient {
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return GitLabClient(baseUrl = "https://gitlab.example.com", token = "t", httpClient = httpClient, responseCache = null)
    }

    @Test
    fun `pipeline jobs are listed with their status`() =
        runTest {
            val paths = mutableListOf<String>()
            val jobs = client(
                HttpStatusCode.OK,
                """[{"id": 1, "name": "test", "status": "failed"}, {"id": 2, "name": "lint", "status": "success"}]""",
                paths,
            ).fetchPipelineJobs("group/app", 42)

            assertEquals(listOf(GitLabJob("test", "failed"), GitLabJob("lint", "success")), jobs)
            // The project id goes through the client's shared encodeProjectId, which URL-encodes the slash.
            assertEquals(listOf("/api/v4/projects/group%2Fapp/pipelines/42/jobs"), paths)
        }

    @Test
    fun `an API error yields null instead of failing the fetch`() =
        runTest {
            val jobs = client(
                HttpStatusCode.Forbidden,
                """{"message": "403 Forbidden"}""",
                mutableListOf()
            ).fetchPipelineJobs("group/app", 42)

            assertNull(jobs)
        }

    @Test
    fun `a cancelled approvals fetch is rethrown and not read as no approvals`() =
        runTest {
            val engine = MockEngine { throw CancellationException("run cancelled") }
            val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
            val gitlab = GitLabClient(baseUrl = "https://gitlab.example.com", token = "t", httpClient = httpClient, responseCache = null)

            assertFailsWith<CancellationException> { gitlab.fetchApprovals("group/app", 7) }
        }
}
