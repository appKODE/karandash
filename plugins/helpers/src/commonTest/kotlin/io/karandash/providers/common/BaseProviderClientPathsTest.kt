package io.karandash.providers.common

import io.karandash.api.exception.ProviderApiException
import io.karandash.helpers.reporter.ReviewdogFormatConfig
import io.karandash.http.HttpAuth
import io.karandash.http.ProviderResponseCache
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The auth retry, rate-limit capture, status checks and decode paths of [BaseProviderClient]. */
class BaseProviderClientPathsTest {
    @Serializable
    private data class Item(
        val id: Int,
    )

    private class PathsClient(
        httpClient: HttpClient,
        cache: ProviderResponseCache? = null,
        private val retryOn401: Boolean = false,
    ) : BaseProviderClient("https://example.com", httpClient, responseCache = cache) {
        override val platformName: String = "Test"
        val prepared = mutableListOf<Unit>()

        override fun credential(): HttpAuth? = null

        override suspend fun prepareAuth() {
            prepared += Unit
        }

        override suspend fun onUnauthorized(): Boolean = retryOn401

        suspend fun status(path: String): Int = trackedRequest { get("$baseUrl$path") }.status.value

        suspend fun checked(path: String) = requireSuccess(trackedRequest { get("$baseUrl$path") }, "GET $path")

        suspend fun raw(path: String): String = cachedGetRaw("$baseUrl$path", "GET $path")

        suspend fun item(path: String): Item = cachedGetAndDecode("$baseUrl$path", "GET $path")

        suspend fun streamed(path: String): Item = streamGetAndDecode("$baseUrl$path", "GET $path")
    }

    private fun client(
        cache: ProviderResponseCache? = null,
        retryOn401: Boolean = false,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = PathsClient(HttpClient(MockEngine(handler)), cache, retryOn401)

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `a 401 is sent once more after onUnauthorized drops the credential`() =
        runTest {
            val calls = mutableListOf<Int>()
            val c = client(retryOn401 = true) {
                calls += calls.size
                if (calls.size == 1) respond("", HttpStatusCode.Unauthorized) else respond("ok", HttpStatusCode.OK)
            }
            assertEquals(200, c.status("/x"))
            assertEquals(2, calls.size)
            assertEquals(2, c.prepared.size)
        }

    @Test
    fun `a 401 stands when onUnauthorized declines`() =
        runTest {
            val c = client { respond("", HttpStatusCode.Unauthorized) }
            assertEquals(401, c.status("/x"))
            assertEquals(1, c.prepared.size)
        }

    @Test
    fun `a full set of rate limit headers is captured and a partial one is not`() =
        runTest {
            val full = client {
                respond(
                    "",
                    HttpStatusCode.OK,
                    headersOf(
                        "X-RateLimit-Remaining" to listOf("7"),
                        "X-RateLimit-Limit" to listOf("60"),
                        "X-RateLimit-Reset" to listOf(" 1700 ")
                    )
                )
            }
            full.status("/x")
            val info = full.lastRateLimit()
            assertEquals(7, info?.remaining)
            assertEquals(60, info?.limit)
            assertEquals(1700L, info?.resetEpochSeconds)

            val partial = client {
                respond(
                    "",
                    HttpStatusCode.OK,
                    headersOf(
                        "RateLimit-Remaining" to listOf("7"),
                        "RateLimit-Limit" to listOf("60")
                    )
                )
            }
            partial.status("/x")
            assertNull(partial.lastRateLimit())
            val none = client { respond("", HttpStatusCode.OK) }
            none.status("/x")
            assertNull(none.lastRateLimit())
        }

    @Test
    fun `checkAuth is true on 2xx false on 401 or 403 and throws otherwise`() =
        runTest {
            assertTrue(client { respond("", HttpStatusCode.OK) }.checkAuth("https://example.com/user"))
            assertFalse(client { respond("", HttpStatusCode.Unauthorized) }.checkAuth("https://example.com/user"))
            assertFalse(client { respond("", HttpStatusCode.Forbidden) }.checkAuth("https://example.com/user"))
            val e = assertFailsWith<ProviderApiException> {
                client { respond("boom", HttpStatusCode.InternalServerError) }.checkAuth("https://example.com/user")
            }
            assertEquals(500, e.statusCode)
        }

    @Test
    fun `requireSuccess passes a 2xx and throws on any other status`() =
        runTest {
            assertEquals(200, client { respond("", HttpStatusCode.OK) }.checked("/x").status.value)
            val e = assertFailsWith<ProviderApiException> { client { respond("nope", HttpStatusCode.NotFound) }.checked("/x") }
            assertEquals(404, e.statusCode)
        }

    @Test
    fun `a cached GET that fails throws with the response status`() =
        runTest {
            val e = assertFailsWith<ProviderApiException> { client { respond("gone", HttpStatusCode.Gone) }.raw("/x") }
            assertEquals(410, e.statusCode)
        }

    @Test
    fun `decode errors name the platform and request`() =
        runTest {
            val malformed =
                assertFailsWith<IllegalStateException> { client { respond("{not json", HttpStatusCode.OK, jsonHeaders) }.item("/x") }
            assertTrue("Test API response parsing failed (GET /x)" in malformed.message.orEmpty(), malformed.message)
            val wrongShape =
                assertFailsWith<IllegalStateException> { client { respond("""{"id":"x"}""", HttpStatusCode.OK, jsonHeaders) }.item("/x") }
            assertTrue("parsing failed" in wrongShape.message.orEmpty(), wrongShape.message)
        }

    @Test
    fun `a streamed GET decodes the bytes and reports status and parse failures`() =
        runTest {
            assertEquals(Item(3), client { respond("""{"id":3}""", HttpStatusCode.OK, jsonHeaders) }.streamed("/x"))
            val status = assertFailsWith<ProviderApiException> { client { respond("bad", HttpStatusCode.BadGateway) }.streamed("/x") }
            assertEquals(502, status.statusCode)
            val parse = assertFailsWith<IllegalStateException> { client { respond("[1]", HttpStatusCode.OK, jsonHeaders) }.streamed("/x") }
            assertTrue("streaming parse failed" in parse.message.orEmpty(), parse.message)
        }

    @Test
    fun `a streamed GET serves a cached body without a second request`() =
        runTest {
            val cache = ProviderResponseCache()
            val calls = mutableListOf<String>()
            val c = client(cache) {
                calls += it.url.toString()
                respond(
                    """{"id":5}""",
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        HttpHeaders.ETag to listOf("\"e\"")
                    )
                )
            }
            assertEquals(Item(5), c.item("/x"))
            assertEquals(Item(5), c.streamed("/x"))
            assertEquals(1, calls.size)
        }

    @Test
    fun `the reviewdog format config defaults to no language`() {
        assertNull(ReviewdogFormatConfig().language)
        assertEquals("ru", ReviewdogFormatConfig("ru").language)
    }
}
