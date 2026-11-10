package io.karandash.platforms.bitbucket

import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BitbucketPaginationTest {
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private fun client(
        logger: Logger = CapturingLogger(),
        engine: MockEngine,
    ): BitbucketClient {
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return BitbucketClient(httpClient = http, logger = logger, responseCache = null, pageSize = PAGE_SIZE)
    }

    /** Serves [total] diffstat entries two per page, linking each page to the next with `next`. */
    private fun diffStatPages(
        total: Int,
        requests: MutableList<String>,
    ) = MockEngine { request ->
        requests += request.url.toString()
        val page = request.url.parameters["page"]?.toInt() ?: 1
        val first = (page - 1) * PAGE_SIZE + 1
        val last = minOf(page * PAGE_SIZE, total)
        val values = (first..last).joinToString(",") { """{"status":"modified","new":{"path":"f$it.kt"}}""" }
        val next = if (last < total) ""","next":"$DIFFSTAT_URL?pagelen=$PAGE_SIZE&page=${page + 1}"""" else ""
        respond("""{"values":[$values]$next}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun `fetchDiffStats follows next across three pages of two`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()

            val stats = client(logger, diffStatPages(5, requests)).fetchDiffStats("w", "r", "1")

            assertEquals((1..5).map { "f$it.kt" }, stats.map { it.newPath })
            assertEquals(3, requests.size)
            assertTrue(requests.first().contains("pagelen=$PAGE_SIZE"), requests.first())
            assertTrue(logger.warnings.isEmpty(), "no truncation warning expected: ${logger.warnings}")
        }

    @Test
    fun `fetchDiffStats warns when the page cap cuts the list`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()

            val stats = client(logger, diffStatPages(10_000, requests)).fetchDiffStats("w", "r", "1")

            assertEquals(requests.size * PAGE_SIZE, stats.size)
            assertTrue(logger.warnings.single().contains("later items are missing"), "${logger.warnings}")
        }

    @Test
    fun `a failing page is an error and not an empty list`() =
        runTest {
            val engine = MockEngine { respond("""{"error":{"message":"nope"}}""", HttpStatusCode.Forbidden) }

            assertFailsWith<Exception> { client(engine = engine).fetchDiffStats("w", "r", "1") }
        }

    private companion object {
        const val PAGE_SIZE = 2
        const val DIFFSTAT_URL = "https://api.bitbucket.org/2.0/repositories/w/r/pullrequests/1/diffstat"
    }
}
