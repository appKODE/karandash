package io.karandash.platforms.bitbucket

import io.karandash.models.CiJobStatus
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BitbucketCiStatusTest {
    @Test
    fun `pull request statuses become one CI job per key`() =
        runTest {
            val paths = mutableListOf<String>()
            val engine = MockEngine { request ->
                paths += request.url.encodedPath
                respond(
                    """
                    {"values": [
                      {"key": "build", "state": "FAILED", "name": "Pipeline #2"},
                      {"key": "build", "state": "SUCCESSFUL", "name": "Pipeline #1"},
                      {"key": "lint", "state": "INPROGRESS"}
                    ]}
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
            val client = BitbucketClient(token = "t", httpClient = httpClient, responseCache = null)

            val jobs = BitbucketAdapter.toCiJobs(client.fetchStatuses("ws", "repo", "7"))

            assertEquals(listOf(CiJobStatus("Pipeline #2", "FAILED"), CiJobStatus("lint", "INPROGRESS")), jobs)
            assertEquals(listOf("/2.0/repositories/ws/repo/pullrequests/7/statuses"), paths)
        }
}
