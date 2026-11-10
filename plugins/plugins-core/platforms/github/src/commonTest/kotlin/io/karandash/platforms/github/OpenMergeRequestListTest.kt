package io.karandash.platforms.github

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
    fun `lists open PR numbers into the base branch`() =
        runTest {
            val urls = mutableListOf<Url>()
            val client = GitHubClient(token = "t", httpClient = http("""[{"number":3},{"number":4}]""", urls))

            assertEquals(listOf("3", "4"), client.listOpenPullRequestNumbers("o", "r", "main", 5))

            val url = urls.single()
            assertEquals("/repos/o/r/pulls", url.encodedPath)
            assertEquals("open", url.parameters["state"])
            assertEquals("main", url.parameters["base"])
            assertEquals("5", url.parameters["per_page"])
        }

    /** similar-issues history comes from the issue search, PRs only, merged told apart from closed. */
    @Test
    fun `searches PRs and tells merged from closed`() =
        runTest {
            val urls = mutableListOf<Url>()
            val body = """{"items":[""" +
                """{"number":42,"title":"Fix SQL","state":"closed","user":{"login":"bob"},""" +
                """"pull_request":{"merged_at":"2026-01-01T00:00:00Z"}},""" +
                """{"number":43,"title":"Abandoned","state":"closed","pull_request":{"merged_at":null}},""" +
                """{"number":44,"title":"Open","state":"open","pull_request":{}}]}"""
            val client = GitHubClient(token = "t", httpClient = http(body, urls))

            val hits = client.searchPullRequests("o", "r", "userRepository", "bob", 5)

            assertEquals(listOf("merged", "closed", "opened"), hits.map { it.state })
            assertEquals("bob", hits.first().author)
            val url = urls.single()
            assertEquals("/search/issues", url.encodedPath)
            assertEquals("repo:o/r is:pr userRepository author:bob", url.parameters["q"])
        }
}
