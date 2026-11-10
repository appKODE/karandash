package io.karandash.platforms.bitbucket

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
    fun `lists open PR ids from the values envelope`() =
        runTest {
            val urls = mutableListOf<Url>()
            val client = BitbucketClient(httpClient = http("""{"values":[{"id":11},{"id":12}]}""", urls), responseCache = null)

            assertEquals(listOf("11", "12"), client.listOpenPullRequestIds("ws", "repo", "main", 5))

            val url = urls.single()
            assertEquals("OPEN", url.parameters["state"])
            assertEquals("destination.branch.name=\"main\"", url.parameters["q"])
            assertEquals("5", url.parameters["pagelen"])
        }
}
