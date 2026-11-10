package io.karandash.platforms.github

import io.karandash.models.CiJobStatus
import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.AdapterHelpers
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubCiAndReviewersTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(body: (String) -> String = { "{}" }): GitHubClient {
        val engine = MockEngine { request ->
            requests += request
            respond(body(request.url.encodedPath), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return GitHubClient(baseUrl = "https://api.github.com", token = "t", httpClient = httpClient, responseCache = null)
    }

    @Test
    fun `statuses and check runs become CI jobs with an aggregate status`() =
        runTest {
            val github = client { path ->
                if (path.endsWith("/status")) {
                    """{"state": "pending", "statuses": [{"context": "ci/lint", "state": "success"}]}"""
                } else {
                    """{"check_runs": [{"name": "build", "status": "completed", "conclusion": "failure"},
                       {"name": "deploy", "status": "in_progress", "conclusion": null}]}"""
                }
            }

            val jobs = GitHubAdapter.toCiJobs(
                github.fetchCombinedStatus("o", "r", "abc"),
                github.fetchCheckRuns("o", "r", "abc"),
            )

            assertEquals(
                listOf(CiJobStatus("ci/lint", "success"), CiJobStatus("build", "failure"), CiJobStatus("deploy", "in_progress")),
                jobs,
            )
            assertEquals("failed", AdapterHelpers.aggregateCiStatus(jobs))
            assertEquals(
                listOf("/repos/o/r/commits/abc/status", "/repos/o/r/commits/abc/check-runs"),
                requests.map { it.url.encodedPath },
            )
        }

    @Test
    fun `requestReviewers posts logins to the requested_reviewers API`() =
        runTest {
            client().requestReviewers("o", "r", 7, listOf("alice", "bob"))

            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/repos/o/r/pulls/7/requested_reviewers", request.url.encodedPath)
            assertEquals(listOf("alice", "bob"), reviewersIn(request))
        }

    @Test
    fun `removeRequestedReviewers deletes logins from the requested_reviewers API`() =
        runTest {
            client().removeRequestedReviewers("o", "r", 7, listOf("alice"))

            val request = requests.single()
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/repos/o/r/pulls/7/requested_reviewers", request.url.encodedPath)
            assertEquals(listOf("alice"), reviewersIn(request))
        }

    private fun reviewersIn(request: HttpRequestData): List<String> {
        val text = (request.body as TextContent).text
        return Json
            .parseToJsonElement(text)
            .jsonObject
            .getValue("reviewers")
            .jsonArray
            .map { it.jsonPrimitive.content }
    }
}
