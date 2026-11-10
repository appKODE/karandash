package io.karandash.platforms.azuredevops

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenMergeRequestListTest {
    private fun http(
        body: String,
        urls: MutableList<Url>,
    ) = HttpClient(
        MockEngine { request ->
            urls += request.url
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    @Test
    fun `lists active PR ids into the target ref`() =
        runTest {
            val urls = mutableListOf<Url>()
            val body = """{"value":[{"pullRequestId":21},{"pullRequestId":22}],"count":2}"""
            val client = AzureDevOpsProviderClient(http(body, urls), token = "t", baseUrl = "https://tfs.corp.test/tfs")

            assertEquals(listOf("21", "22"), client.listActivePullRequestIds("c", "p", "r", "main", 5))

            val url = urls.single()
            assertEquals("active", url.parameters["searchCriteria.status"])
            assertEquals("refs/heads/main", url.parameters["searchCriteria.targetRefName"])
            assertEquals("5", url.parameters["\$top"])
        }
}
