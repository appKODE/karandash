package io.karandash.platforms.gitea

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

class GiteaCiStatusTest {
    @Test
    fun `combined status of the head commit lists each status context`() =
        runTest {
            val paths = mutableListOf<String>()
            val engine = MockEngine { request ->
                paths += request.url.encodedPath
                respond(
                    """{"state": "failure", "statuses": [{"context": "ci/build", "status": "failure"}, {"context": "lint", "status": "success"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
            val client = GiteaProviderClient("https://gitea.example.com", httpClient, "t")

            val combined = client.fetchCombinedStatus("o", "r", "abc")

            assertEquals(listOf("ci/build" to "failure", "lint" to "success"), combined.statuses.map { it.context to it.status })
            assertEquals(listOf("/api/v1/repos/o/r/commits/abc/status"), paths)
        }
}
