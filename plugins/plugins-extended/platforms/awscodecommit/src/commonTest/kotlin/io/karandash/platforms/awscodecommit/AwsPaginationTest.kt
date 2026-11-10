package io.karandash.platforms.awscodecommit

import io.karandash.sdk.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwsPaginationTest {
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    /** GetDifferences serving [total] differences two per page, chained with `NextToken`. */
    private fun differences(
        total: Int,
        bodies: MutableList<String>,
    ) = MockEngine { request ->
        assertEquals("CodeCommit_20150413.GetDifferences", request.headers["X-Amz-Target"])
        val body = (request.body as TextContent).text
        bodies += body
        val page = Regex(""""NextToken":"p(\d+)"""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 1
        val first = (page - 1) * PAGE_SIZE + 1
        val last = minOf(page * PAGE_SIZE, total)
        val items = (first..last).joinToString(",") { """{"changeType":"M","afterBlob":{"path":"f$it.kt"}}""" }
        val next = if (last < total) ""","NextToken":"p${page + 1}"""" else ""
        respond("""{"differences":[$items]$next}""", headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1"))
    }

    private fun client(
        engine: MockEngine,
        logger: Logger,
    ) = AwsCodeSuiteClient(accessKeyId = "k", secretAccessKey = "s", httpClient = HttpClient(engine), logger = logger)

    @Test
    fun `fetchDifferences follows NextToken across three pages of two`() =
        runTest {
            val bodies = mutableListOf<String>()
            val logger = CapturingLogger()

            val diffs = client(differences(5, bodies), logger).fetchDifferences("repo", "base", "head")

            assertEquals((1..5).map { "f$it.kt" }, diffs.map { it.afterBlob?.path })
            assertEquals(3, bodies.size)
            assertTrue("NextToken" !in bodies.first(), bodies.first())
            assertTrue(logger.warnings.isEmpty(), "${logger.warnings}")
        }

    @Test
    fun `fetchDifferences warns when the page cap cuts the list`() =
        runTest {
            val bodies = mutableListOf<String>()
            val logger = CapturingLogger()

            val diffs = client(differences(10_000, bodies), logger).fetchDifferences("repo", "base", "head")

            assertEquals(bodies.size * PAGE_SIZE, diffs.size)
            assertTrue(logger.warnings.single().contains("later items are missing"), "${logger.warnings}")
        }

    private companion object {
        const val PAGE_SIZE = 2
    }
}
