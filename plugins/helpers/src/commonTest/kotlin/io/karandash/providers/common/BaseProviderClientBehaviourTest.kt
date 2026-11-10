package io.karandash.providers.common

import io.karandash.api.exception.ProviderApiException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.crypto.sha256Hex
import io.karandash.http.CachedHttpResponse
import io.karandash.http.ETagTieredCache
import io.karandash.http.HttpAuth
import io.karandash.http.ProviderResponseCache
import io.karandash.sdk.Logger
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class BaseProviderClientBehaviourTest {
    @Serializable
    private data class Item(
        val n: Int,
    )

    private class Client(
        engine: MockEngine,
        logger: Logger = io.karandash.sdk.NoOpLogger,
        cache: ProviderResponseCache? = null,
        private val retryUnauthorized: Boolean = false,
    ) : BaseProviderClient("https://example.com", HttpClient(engine), logger = logger, responseCache = cache) {
        override val platformName = "Test"
        var refreshed = 0

        override fun credential(): HttpAuth? = null

        override suspend fun onUnauthorized(): Boolean {
            refreshed++
            return retryUnauthorized && refreshed == 1
        }

        suspend fun auth(path: String) = checkAuth("$baseUrl$path")

        suspend fun send(body: JsonObject) = sendJson(HttpMethod.Put, "$baseUrl/x", body)

        suspend fun text(path: String) = getTextOrNull("$baseUrl$path")

        suspend fun item(path: String): Item? = getOrNull<Item>("$baseUrl$path")

        fun parse(raw: String): Item = decodeBody(raw, "label")

        suspend fun stream(path: String): Item = streamGetAndDecode("$baseUrl$path", "stream")

        suspend fun bytes(path: String) = streamGetRawBytes("$baseUrl$path", "raw")

        suspend fun pages(cap: Int? = null): List<Item> = getAllPages("$baseUrl/p?page=1", "pages", cap)

        suspend fun file(
            path: String,
            limit: Long = PlatformServiceModule.MAX_FILE_CONTENT_BYTES.toLong(),
            decode: (ByteArray) -> ByteArray? = { it },
        ) = getFileTextOrNull("$baseUrl$path", "file", limit, decode)

        fun asText(bytes: ByteArray) = fileText(bytes, "f")

        suspend fun fresh() = untrackedRequest { this }
    }

    private fun engine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = MockEngine(handler)

    private fun cache() = ProviderResponseCache()

    @Test
    fun `check auth is true on success false on 401 or 403 and throws otherwise`() =
        runTest {
            val client = Client(
                engine { req ->
                    when (req.url.encodedPath) {
                        "/ok" -> respond("", HttpStatusCode.OK)
                        "/no" -> respond("", HttpStatusCode.Unauthorized)
                        "/forbidden" -> respond("", HttpStatusCode.Forbidden)
                        else -> respond("down", HttpStatusCode.InternalServerError)
                    }
                }
            )

            assertTrue(client.auth("/ok"))
            assertEquals(false, client.auth("/no"))
            assertEquals(false, client.auth("/forbidden"))
            val failure = assertFailsWith<ProviderApiException> { client.auth("/boom") }
            assertEquals(500, failure.statusCode)
        }

    @Test
    fun `a 401 is retried once when the client can refresh its credential`() =
        runTest {
            var calls = 0
            val client = Client(
                engine { if (calls++ == 0) respond("", HttpStatusCode.Unauthorized) else respond("fine", HttpStatusCode.OK) },
                retryUnauthorized = true,
            )

            assertEquals("fine", client.text("/x"))
            assertEquals(2, calls)
            assertEquals(1, client.refreshed)
        }

    @Test
    fun `send json returns the body and reports failures`() =
        runTest {
            var seen = ""
            val ok = Client(
                engine { req ->
                    seen = req.method.value + " " + req.body.toString()
                    respond("done", HttpStatusCode.OK)
                }
            )
            val bad = Client(engine { respond("nope", HttpStatusCode.UnprocessableEntity) })

            assertEquals("done", ok.send(JsonObject(mapOf("a" to JsonPrimitive(1)))))
            assertTrue(seen.startsWith("PUT"), seen)
            assertFailsWith<ProviderApiException> { bad.send(JsonObject(emptyMap())) }
        }

    @Test
    fun `get or null maps 404 to null and reports unreadable bodies`() =
        runTest {
            val client = Client(
                engine { req ->
                    when (req.url.encodedPath) {
                        "/good" -> respond("""{"n":3}""", HttpStatusCode.OK)
                        "/bad" -> respond("""{"n":"x"}""", HttpStatusCode.OK)
                        "/arg" -> respond("[1]", HttpStatusCode.OK)
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }
            )

            assertEquals(Item(3), client.item("/good"))
            assertNull(client.item("/missing"))
            assertNull(client.text("/missing"))
            val parse = assertFailsWith<IllegalStateException> { client.item("/bad") }
            assertTrue(parse.message.orEmpty().startsWith("Test API response parsing failed (GET https://example.com/bad)"), parse.message)
            assertFailsWith<IllegalStateException> { client.parse("not json at all") }
        }

    @Test
    fun `streaming decode reads a miss from the wire and a fresh entry from the cache`() =
        runTest {
            val url = "https://example.com/s"
            val backing = TieredCache<String, CachedHttpResponse>(name = "fresh-test", maxEntries = 4, tier = CacheTier.MEMORY_ONLY)
            backing.put(
                sha256Hex("Test:GET:$url"),
                CachedHttpResponse(
                    body = """{"n":4}""",
                    etag = null,
                    storedAtEpochSeconds = Clock.System.now().epochSeconds,
                    ttlSeconds = 3_600,
                ),
            )
            var calls = 0
            val cached = Client(
                engine {
                    calls++
                    respond("", HttpStatusCode.InternalServerError)
                },
                cache = ProviderResponseCache(ETagTieredCache(backing, 1.minutes)),
            )
            val plain = Client(engine { respond("""{"n":5}""", HttpStatusCode.OK) })

            assertEquals(Item(5), plain.stream("/s"))
            assertEquals(Item(4), cached.stream("/s"))
            assertEquals(0, calls)
        }

    @Test
    fun `streaming decode names the request when the body does not parse`() =
        runTest {
            val client = Client(engine { respond("""{"n":"x"}""", HttpStatusCode.OK) })

            val failure = assertFailsWith<IllegalStateException> { client.stream("/s") }

            assertTrue(failure.message.orEmpty().startsWith("Test API streaming parse failed (stream)"), failure.message)
        }

    @Test
    fun `raw bytes surface an error status as a provider exception`() =
        runTest {
            val client = Client(engine { respond("denied", HttpStatusCode.Forbidden) })

            val failure = assertFailsWith<ProviderApiException> { client.bytes("/b") }

            assertEquals(403, failure.statusCode)
        }

    @Test
    fun `rate limit headers are remembered from either header family`() =
        runTest {
            val client = Client(
                engine {
                    respond(
                        "x",
                        HttpStatusCode.OK,
                        headersOf(
                            "RateLimit-Limit" to listOf("100"),
                            "RateLimit-Remaining" to listOf("7"),
                            "RateLimit-Reset" to listOf("1700000000")
                        )
                    )
                }
            )
            assertNull(client.lastRateLimit())

            client.text("/x")

            val info = client.lastRateLimit()
            assertEquals(7, info?.remaining)
            assertEquals(100, info?.limit)
            assertEquals(1_700_000_000L, info?.resetEpochSeconds)
        }

    @Test
    fun `get all pages follows the next link and stops at the cap with a warning`() =
        runTest {
            val context = TestPluginContext()
            val client = Client(
                engine { req ->
                    val page = req.url.parameters["page"]?.toInt() ?: 1
                    respond(
                        """[{"n":$page}]""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.Link, "<https://example.com/p?page=${page + 1}>; rel=\"next\""),
                    )
                },
                logger = context.logger,
            )

            val items = client.pages(cap = 2)

            assertEquals(listOf(Item(1), Item(2)), items)
            assertEquals(1, context.warnings.size)
            assertTrue(context.warnings[0].contains("stopped after 2 pages"), context.warnings[0])
        }

    @Test
    fun `streaming decode revalidates an expired entry with its etag`() =
        runTest {
            val url = "https://example.com/s"
            val backing = TieredCache<String, CachedHttpResponse>(name = "stale-test", maxEntries = 4, tier = CacheTier.MEMORY_ONLY)
            backing.put(
                sha256Hex("Test:GET:$url"),
                CachedHttpResponse(
                    body = """{"n":8}""",
                    etag = "\"v1\"",
                    storedAtEpochSeconds = Clock.System.now().epochSeconds - 3_600,
                    ttlSeconds = 60,
                ),
            )
            val sent = mutableListOf<String?>()
            val client = Client(
                engine { req ->
                    sent += req.headers[HttpHeaders.IfNoneMatch]
                    respond("", HttpStatusCode.NotModified)
                },
                cache = ProviderResponseCache(ETagTieredCache(backing, 1.minutes)),
            )

            assertEquals(Item(8), client.stream("/s"))
            assertEquals(listOf<String?>("\"v1\""), sent)
        }

    @Test
    fun `a page error carries the status and a capped body`() =
        runTest {
            val client = Client(engine { respond("e".repeat(900), HttpStatusCode.BadGateway) })

            val failure = assertFailsWith<ProviderApiException> { client.pages() }

            assertEquals(502, failure.statusCode)
            assertTrue(failure.message.orEmpty().length < 800, failure.message)
        }

    @Test
    fun `file text skips missing oversized and binary files and decodes the rest`() =
        runTest {
            val context = TestPluginContext()
            val big = ByteArray(40) { 'a'.code.toByte() }
            val client = Client(
                engine { req ->
                    when (req.url.encodedPath) {
                        "/text" -> respond("hello", HttpStatusCode.OK)
                        "/big" -> respond(big, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "40"))
                        "/bin" -> respond(byteArrayOf(65, 0, 66), HttpStatusCode.OK)
                        "/fail" -> respond("x", HttpStatusCode.InternalServerError)
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                },
                logger = context.logger,
            )

            assertEquals("hello", client.file("/text"))
            assertEquals("HELLO", client.file("/text", decode = { it.decodeToString().uppercase().encodeToByteArray() }))
            assertNull(client.file("/text", decode = { null }))
            assertNull(client.file("/missing"))
            assertNull(client.file("/big", limit = 10))
            assertNull(client.file("/bin"))
            assertFailsWith<ProviderApiException> { client.file("/fail") }
            assertEquals(1, context.warnings.size)
            assertTrue(context.warnings[0].startsWith("Skipping file"), context.warnings[0])
        }

    @Test
    fun `file text reads a 404 body to the end instead of abandoning it`() =
        runTest {
            val body = ByteReadChannel("not found")
            val client = Client(engine { respond(body, HttpStatusCode.NotFound) })

            assertNull(client.file("/missing"))
            assertTrue(body.isClosedForRead)
        }

    @Test
    fun `file text without a declared length is cut one byte past the limit`() =
        runTest {
            val context = TestPluginContext()
            val client = Client(engine { respond("0123456789ABCDEF", HttpStatusCode.OK) }, logger = context.logger)

            assertNull(client.file("/chunked", limit = 8))
            assertEquals(1, context.warnings.size)
        }

    @Test
    fun `file text rejects bytes over the cap and binary bytes`() {
        val context = TestPluginContext()
        val client = Client(engine { respond("", HttpStatusCode.OK) }, logger = context.logger)

        assertEquals("ok", client.asText("ok".encodeToByteArray()))
        assertNull(client.asText(ByteArray(PlatformServiceModule.MAX_FILE_CONTENT_BYTES + 1)))
        assertNull(client.asText(byteArrayOf(1, 0)))
        assertEquals(1, context.warnings.size)
        assertEquals(1, context.logs.count { it.contains("binary") })
    }

    @Test
    fun `an untracked request skips the credential preparation`() =
        runTest {
            val client = Client(engine { respond("", HttpStatusCode.OK) })

            assertTrue(client.fresh() is HttpClient)
        }
}
