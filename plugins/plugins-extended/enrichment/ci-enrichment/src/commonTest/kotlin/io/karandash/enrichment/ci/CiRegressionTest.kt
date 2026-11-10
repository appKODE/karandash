package io.karandash.enrichment.ci

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** a huge CI job trace is cut to its tail while it streams. */
class CiRegressionTest {
    private fun client(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = gitLabClient(
        baseUrl = "https://gitlab.example.com",
        projectId = "1",
        token = "t",
        engine = MockEngine { respond(body, status) },
    )

    @Test
    fun `a large trace keeps only its tail`() =
        runTest {
            val trace = "head line\n" + "x".repeat(2_000_000) + "\nlast line\n"
            val tail = client(trace).fetchJobTrace(5)

            assertTrue(tail != null && tail.length < 300_000, "trace was not cut: ${tail?.length}")
            assertTrue(tail.endsWith("last line\n"))
        }

    @Test
    fun `the tail is exactly the last bytes across ring wraps`() =
        runTest {
            val trace = (0 until 400_000).joinToString("") { ('a' + it % 26).toString() }
            val tail = client(trace).fetchJobTrace(5)

            assertEquals(trace.takeLast(128 * 1024), tail)
        }

    @Test
    fun `a small trace is returned whole`() =
        runTest {
            assertEquals("a\nb\n", client("a\nb\n").fetchJobTrace(5))
        }

    @Test
    fun `a failed trace request gives null`() =
        runTest {
            assertNull(client("nope", HttpStatusCode.NotFound).fetchJobTrace(5))
        }
}
