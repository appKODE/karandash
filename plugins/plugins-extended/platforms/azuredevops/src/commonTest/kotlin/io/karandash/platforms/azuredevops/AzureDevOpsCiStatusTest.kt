package io.karandash.platforms.azuredevops

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

class AzureDevOpsCiStatusTest {
    @Test
    fun `pull request statuses become CI jobs - latest per context`() =
        runTest {
            val paths = mutableListOf<String>()
            val engine = MockEngine { request ->
                paths += request.url.encodedPath
                respond(
                    """
                    {"value": [
                      {"state": "pending", "context": {"name": "build", "genre": "ci"}},
                      {"state": "succeeded", "context": {"name": "build", "genre": "ci"}},
                      {"state": "notApplicable", "context": {"name": "policy"}},
                      {"state": "failed", "context": {"name": "scan"}}
                    ]}
                    """.trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
            val client = AzureDevOpsProviderClient(httpClient, token = "t")

            val jobs = AzureDevOpsAdapter.toCiJobs(client.fetchStatuses("org", "proj", "repo", "7"))

            assertEquals(listOf(CiJobStatus("ci/build", "succeeded"), CiJobStatus("scan", "failed")), jobs)
            assertEquals(listOf("/org/proj/_apis/git/repositories/repo/pullRequests/7/statuses"), paths)
        }
}
