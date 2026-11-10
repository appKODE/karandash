package io.karandash.platforms.azuredevops

import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AzureDevOpsPaginationTest {
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(
        engine: MockEngine,
        logger: Logger,
    ) = AzureDevOpsProviderClient(
        HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } },
        token = "t",
        logger = logger,
        pageSize = PAGE_SIZE,
    )

    /** Pull request commits, two per page, chained with the `x-ms-continuationtoken` header. */
    private fun commitPages(
        total: Int,
        requests: MutableList<String>,
    ) = MockEngine { request ->
        requests += request.url.toString()
        val page = request.url.parameters["continuationToken"]?.toInt() ?: 1
        val first = (page - 1) * PAGE_SIZE + 1
        val last = minOf(page * PAGE_SIZE, total)
        val author = """{"name":"a","email":"a@x","date":"2026-01-01"}"""
        val commits = (first..last).joinToString(",") { """{"commitId":"c$it","comment":"m","author":$author,"committer":$author}""" }
        val headers = if (last < total) {
            headersOf(
                HttpHeaders.ContentType to listOf("application/json"),
                "x-ms-continuationtoken" to listOf("${page + 1}"),
            )
        } else {
            json
        }
        respond("""{"value":[$commits],"count":${last - first + 1}}""", headers = headers)
    }

    @Test
    fun `fetchCommits follows the continuation token across three pages of two`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()

            val commits = client(commitPages(5, requests), logger).fetchCommits("org", "proj", "repo", "7")

            assertEquals((1..5).map { "c$it" }, commits.map { it.commitId })
            assertEquals(3, requests.size)
            assertTrue(requests.all { "%24top=$PAGE_SIZE" in it || "\$top=$PAGE_SIZE" in it }, "$requests")
            assertTrue(logger.warnings.isEmpty(), "${logger.warnings}")
        }

    @Test
    fun `fetchCommits warns when the page cap cuts the list`() =
        runTest {
            val requests = mutableListOf<String>()
            val logger = CapturingLogger()

            val commits = client(commitPages(10_000, requests), logger).fetchCommits("org", "proj", "repo", "7")

            assertEquals(requests.size * PAGE_SIZE, commits.size)
            assertTrue(logger.warnings.single().contains("later items are missing"), "${logger.warnings}")
        }

    @Test
    fun `fetchChanges follows nextSkip across three pages of two`() =
        runTest {
            val requests = mutableListOf<String>()
            val engine = MockEngine { request ->
                requests += request.url.toString()
                if (request.url.encodedPath.endsWith("/iterations")) {
                    return@MockEngine respond("""{"value":[{"id":1},{"id":3}]}""", headers = json)
                }
                assertTrue(request.url.encodedPath.endsWith("/iterations/3/changes"), request.url.encodedPath)
                val skip = request.url.parameters["\$skip"]?.toInt() ?: 0
                val last = minOf(skip + PAGE_SIZE, 5)
                val entries = (skip + 1..last).joinToString(",") { """{"changeType":"edit","item":{"path":"/f$it.kt"}}""" }
                val nextSkip = if (last < 5) last else 0
                respond("""{"changeEntries":[$entries],"nextSkip":$nextSkip}""", headers = json)
            }
            val logger = CapturingLogger()

            val changes = client(engine, logger).fetchChanges("org", "proj", "repo", "7")

            assertEquals((1..5).map { "/f$it.kt" }, changes.map { it.item?.path })
            assertEquals(4, requests.size)
            assertTrue(logger.warnings.isEmpty(), "${logger.warnings}")
        }

    @Test
    fun `fetchChanges and fetchFileCommits warn instead of silently returning nothing`() =
        runTest {
            val logger = CapturingLogger()
            val engine = MockEngine {
                respond(
                    "<html>sign in</html>",
                    HttpStatusCode.NonAuthoritativeInformation,
                    headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString())
                )
            }
            val client = client(engine, logger)

            assertEquals(emptyList(), client.fetchChanges("org", "proj", "repo", "7"))
            assertEquals(emptyList(), client.fetchFileCommits("org", "proj", "repo", "/a.kt", "main"))

            assertEquals(2, logger.warnings.size, "${logger.warnings}")
            assertTrue(logger.warnings.all { "could not fetch" in it }, "${logger.warnings}")
        }

    @Test
    fun `fetchFileCommits warns on an HTTP error`() =
        runTest {
            val logger = CapturingLogger()
            val engine = MockEngine { respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, json) }

            assertEquals(emptyList(), client(engine, logger).fetchFileCommits("org", "proj", "repo", "/a.kt", "main"))
            assertTrue(logger.warnings.single().contains("HTTP 500"), "${logger.warnings}")
        }

    @Test
    fun `fetchChanges rethrows cancellation`() =
        runTest {
            val engine = MockEngine { throw CancellationException("cancelled") }

            assertFailsWith<CancellationException> { client(engine, CapturingLogger()).fetchChanges("org", "proj", "repo", "7") }
        }

    private companion object {
        const val PAGE_SIZE = 2
    }
}
