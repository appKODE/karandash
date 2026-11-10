@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gitlab

import io.karandash.api.platform.MergeRequestSearchHit
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
    fun `lists open MR iids into the target branch`() =
        runTest {
            val urls = mutableListOf<Url>()
            val client = GitLabClient(token = "t", httpClient = http("""[{"iid":7},{"iid":9}]""", urls), responseCache = null)

            assertEquals(listOf("7", "9"), client.listOpenMergeRequestIids("g/p", "main", 5))

            val url = urls.single()
            // GitLab needs a path-style project id URL-encoded
            assertEquals("/api/v4/projects/g%2Fp/merge_requests", url.encodedPath)
            assertEquals("opened", url.parameters["state"])
            assertEquals("main", url.parameters["target_branch"])
            assertEquals("5", url.parameters["per_page"])
        }

    /** similar-issues history comes from a search over MRs in any state. */
    @Test
    fun `searches MRs in any state by text and author`() =
        runTest {
            val urls = mutableListOf<Url>()
            val body = """[{"iid":42,"title":"Fix SQL","state":"merged","author":{"username":"bob"},"web_url":"https://g/42"}]"""
            val client = GitLabClient(token = "t", httpClient = http(body, urls), responseCache = null)

            val hit = client.searchMergeRequests("g/p", "userRepository", "bob", 5).single()

            assertEquals(MergeRequestSearchHit("42", "Fix SQL", MergeRequestSearchHit.MERGED, "bob", "https://g/42"), hit)
            val url = urls.single()
            assertEquals("all", url.parameters["state"])
            assertEquals("userRepository", url.parameters["search"])
            assertEquals("bob", url.parameters["author_username"])
        }
}
