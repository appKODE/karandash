package io.karandash.platforms.github

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

class GitHubPaginationTest {
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    /** Serves [total] files two per page, linking each page to the next with a `Link` header. */
    private fun client(
        total: Int,
        logger: Logger,
        requests: MutableList<String>,
    ): GitHubClient {
        val engine = MockEngine { request ->
            requests += request.url.toString()
            val page = request.url.parameters["page"]?.toInt() ?: 1
            val first = (page - 1) * PAGE_SIZE + 1
            val last = minOf(page * PAGE_SIZE, total)
            val body = (first..last).joinToString(",", "[", "]") { """{"filename":"f$it.kt","status":"modified"}""" }
            val headers = if (last < total) {
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.Link to listOf("<$BASE/repos/o/r/pulls/1/files?per_page=$PAGE_SIZE&page=${page + 1}>; rel=\"next\""),
                )
            } else {
                headersOf(HttpHeaders.ContentType, "application/json")
            }
            respond(body, headers = headers)
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return GitHubClient(baseUrl = BASE, httpClient = http, logger = logger, responseCache = null, pageSize = PAGE_SIZE)
    }

    @Test
    fun `fetchFiles follows the Link header across three pages of two`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()

            val files = client(total = 5, logger = logger, requests = requests).fetchFiles("o", "r", 1)

            assertEquals((1..5).map { "f$it.kt" }, files.map { it.filename })
            assertEquals(3, requests.size)
            assertTrue(requests.first().contains("per_page=$PAGE_SIZE"))
            assertTrue(logger.warnings.isEmpty(), "no truncation warning expected: ${logger.warnings}")
        }

    @Test
    fun `fetchFiles warns when the page cap cuts the list`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()

            val files = client(total = 10_000, logger = logger, requests = requests).fetchFiles("o", "r", 1)

            assertEquals(requests.size * PAGE_SIZE, files.size)
            assertTrue(logger.warnings.single().contains("later items are missing"), "${logger.warnings}")
        }

    @Test
    fun `fetchCurrentUsername warns instead of silently returning null on an unreadable response`() =
        runTest {
            val logger = CapturingLogger()
            val engine = MockEngine { respond("<html>", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
            val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
            val client = GitHubClient(baseUrl = BASE, httpClient = http, logger = logger, responseCache = null)

            assertEquals(null, client.fetchCurrentUsername())
            assertTrue(logger.warnings.single().startsWith("GitHub fetchCurrentUsername failed"), "${logger.warnings}")
        }

    private companion object {
        const val BASE = "https://api.github.com"
        const val PAGE_SIZE = 2
    }
}
