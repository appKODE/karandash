package io.karandash.http

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SsrfRedirectSchemeTest {
    private fun redirectingEngine(
        requested: MutableList<String>,
        location: String,
    ) = MockEngine { request ->
        requested += request.url.toString()
        if (request.url.encodedPath == "/start") {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
        } else {
            respond("ok", HttpStatusCode.OK)
        }
    }

    @Test
    fun `an http hop of a chain that started on https is blocked`() =
        runTest {
            val requested = mutableListOf<String>()
            val client = ProviderHttpClientFactory.create(engine = redirectingEngine(requested, "http://example.com/next"))

            val error = assertFailsWith<IllegalArgumentException> {
                client.get("http://example.com/next") { attributes.put(OriginProtocol, URLProtocol.HTTPS) }
            }
            assertTrue(error.message.orEmpty().contains("SSRF protection blocked"), error.message)
            assertEquals(emptyList(), requested)
            client.close()
        }

    @Test
    fun `an https redirect to http on the same host is never requested`() =
        runTest {
            val requested = mutableListOf<String>()
            val client = ProviderHttpClientFactory.create(engine = redirectingEngine(requested, "http://example.com/next"))

            client.get("https://example.com/start")
            assertEquals(listOf("https://example.com/start"), requested)
            client.close()
        }

    @Test
    fun `an http redirect to http is followed`() =
        runTest {
            val requested = mutableListOf<String>()
            val client = ProviderHttpClientFactory.create(engine = redirectingEngine(requested, "http://example.com/next"))

            assertEquals(HttpStatusCode.OK, client.get("http://example.com/start").status)
            assertEquals(listOf("http://example.com/start", "http://example.com/next"), requested)
            client.close()
        }
}
