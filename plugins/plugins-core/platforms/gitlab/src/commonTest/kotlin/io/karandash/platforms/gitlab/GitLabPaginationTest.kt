package io.karandash.platforms.gitlab

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

class GitLabPaginationTest {
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    /** Serves [total] commits two per page, naming the next page in `x-next-page`. */
    private fun client(
        total: Int,
        logger: Logger,
        requests: MutableList<String>,
    ): GitLabClient {
        val engine = MockEngine { request ->
            requests += request.url.toString()
            val page = request.url.parameters["page"]?.toInt() ?: 1
            val first = (page - 1) * PAGE_SIZE + 1
            val last = minOf(page * PAGE_SIZE, total)
            val body = (first..last).joinToString(",", "[", "]") {
                """{"id":"c$it","short_id":"c$it","title":"t","message":"m","author_name":"a","author_email":"a@x","created_at":"2026-01-01"}"""
            }
            val next = if (last < total) "${page + 1}" else ""
            respond(
                body,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "x-next-page" to listOf(next),
                ),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return GitLabClient(httpClient = http, logger = logger, responseCache = null, pageSize = PAGE_SIZE)
    }

    @Test
    fun `fetchCommits follows x-next-page across three pages of two`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()

            val commits = client(total = 5, logger = logger, requests = requests).fetchCommits("g/p", 1)

            assertEquals((1..5).map { "c$it" }, commits.map { it.id })
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.contains("per_page=$PAGE_SIZE") }, "$requests")
            assertTrue(logger.warnings.isEmpty(), "no truncation warning expected: ${logger.warnings}")
        }

    @Test
    fun `fetchCommits warns when the page cap cuts the list`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()

            val commits = client(total = 10_000, logger = logger, requests = requests).fetchCommits("g/p", 1)

            assertEquals(requests.size * PAGE_SIZE, commits.size)
            assertTrue(logger.warnings.single().contains("later items are missing"), "${logger.warnings}")
        }

    private companion object {
        const val PAGE_SIZE = 2
    }
}
