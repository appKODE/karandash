package io.karandash.platforms.gitea

import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
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
import kotlin.test.assertTrue

class GiteaPaginationTest {
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    /**
     * Serves [total] items [served] per page whatever `limit` asks for; with [link], each page but the last names
     * the next one in a `Link` header, as Gitea does.
     */
    private fun pages(
        total: Int,
        served: Int,
        link: Boolean,
        requests: MutableList<String>,
        item: (Int) -> String,
    ) = MockEngine { request ->
        requests += request.url.toString()
        val page = request.url.parameters["page"]?.toInt() ?: 1
        val first = (page - 1) * served + 1
        val last = minOf(page * served, total)
        val body = (first..last).joinToString(",", "[", "]") { item(it) }
        val headers = if (link && last < total) {
            headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                HttpHeaders.Link to listOf("<${request.url.toString().substringBefore("&page=")}&page=${page + 1}>; rel=\"next\""),
            )
        } else {
            headersOf(HttpHeaders.ContentType, "application/json")
        }
        respond(body, headers = headers)
    }

    private fun http(engine: MockEngine) = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }

    private fun comment(id: Int) = """{"id":$id,"body":"c$id"}"""

    private fun label(id: Int) = """{"id":$id,"name":"l$id"}"""

    private fun file(id: Int) = """{"filename":"f$id.kt","status":"modified"}"""

    @Test
    fun `listLabels reads three full pages of two`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()
            val client = GiteaClient(BASE, "t", http(pages(5, 2, link = false, requests, ::label)), logger = logger, pageSize = 2)

            val comments = client.listLabels("o", "r")

            assertEquals((1L..5L).toList(), comments.map { it.id })
            assertEquals(3, requests.size)
            assertTrue(requests.all { "limit=2" in it }, "$requests")
            assertTrue(logger.warnings.isEmpty(), "${logger.warnings}")
        }

    @Test
    fun `listLabels follows the Link header when Gitea serves fewer items than limit`() =
        runTest {
            val requests = mutableListOf<String>()
            val client = GiteaClient(BASE, "t", http(pages(5, 2, link = true, requests, ::label)), pageSize = 3)

            val comments = client.listLabels("o", "r")

            assertEquals((1L..5L).toList(), comments.map { it.id })
            assertEquals(3, requests.size)
        }

    @Test
    fun `listLabels warns when the page cap cuts the list`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()
            val client = GiteaClient(BASE, "t", http(pages(10_000, 2, link = true, requests, ::label)), logger = logger, pageSize = 2)

            val comments = client.listLabels("o", "r")

            assertEquals(requests.size * 2, comments.size)
            assertTrue(logger.warnings.single().contains("later items are missing"), "${logger.warnings}")
        }

    @Test
    fun `listComments reads the unpaginated endpoint once`() =
        runTest {
            val requests = mutableListOf<String>()
            val all = (1..5).joinToString(",", "[", "]") { comment(it) }
            val engine = MockEngine { request ->
                requests += request.url.toString()
                respond(all, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = GiteaClient(BASE, "t", http(engine), pageSize = 2)

            val comments = client.listComments("o", "r", 1)

            assertEquals((1L..5L).toList(), comments.map { it.id })
            assertEquals(listOf("$BASE/api/v1/repos/o/r/issues/1/comments"), requests)
        }

    @Test
    fun `provider fetchPullRequestFiles reads three pages of two following Link past short pages`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()
            val engine = pages(5, 2, link = true, requests, ::file)
            val client = GiteaProviderClient(BASE, http(engine), "t", logger = logger, pageSize = 3)

            val files = client.fetchPullRequestFiles("o", "r", "1")

            assertEquals((1..5).map { "f$it.kt" }, files.map { it.filename })
            assertEquals(3, requests.size)
            assertTrue(logger.warnings.isEmpty(), "${logger.warnings}")
        }

    @Test
    fun `provider fetchPullRequestFiles warns when the page cap cuts the list`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()
            val client =
                GiteaProviderClient(BASE, http(pages(10_000, 2, link = false, requests, ::file)), "t", logger = logger, pageSize = 2)

            val files = client.fetchPullRequestFiles("o", "r", "1")

            assertEquals(requests.size * 2, files.size)
            assertTrue(logger.warnings.single().contains("later items are missing"), "${logger.warnings}")
        }

    private companion object {
        const val BASE = "https://gitea.example.com"
    }
}
