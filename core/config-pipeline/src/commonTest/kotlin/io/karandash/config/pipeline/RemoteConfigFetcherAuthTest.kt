package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.http.HttpAuth
import io.karandash.manifest.ConfigSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [RemoteConfigFetcher] authentication header handling.
 *
 * Uses Ktor [MockEngine] to capture and verify request headers without making real HTTP calls.
 */
class RemoteConfigFetcherAuthTest {
    /** Creates a [RemoteConfigFetcher] backed by [MockEngine] that captures request headers. */
    private fun fetcherWithHeaderCapture(headerCapture: MutableMap<String, String>): RemoteConfigFetcher {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    // Capture all request headers
                    request.headers.forEach { key, values ->
                        headerCapture[key] = values.firstOrNull() ?: ""
                    }
                    // Return 200 OK with minimal content
                    respond(
                        content = ByteReadChannel("# config"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/yaml"),
                    )
                }
            }
        }
        return RemoteConfigFetcher(client)
    }

    @Test
    fun testBearerTokenSetsAuthorizationHeader() =
        runTest {
            val headers = mutableMapOf<String, String>()
            val fetcher = fetcherWithHeaderCapture(headers)
            val source = ConfigSource(
                url = "https://example.com/config.yaml",
                auth = HttpAuth.Bearer("mytoken")
            )

            fetcher.fetch(source)

            assertTrue(headers.containsKey("Authorization"), "Authorization header should be present")
            assertEquals("Bearer mytoken", headers["Authorization"], "Bearer token format incorrect")
        }

    @Test
    fun testBasicAuthSetsAuthorizationHeader() =
        runTest {
            val headers = mutableMapOf<String, String>()
            val fetcher = fetcherWithHeaderCapture(headers)
            val source = ConfigSource(
                url = "https://example.com/config.yaml",
                auth = HttpAuth.Basic("user", "pass")
            )

            fetcher.fetch(source)

            assertTrue(headers.containsKey("Authorization"), "Authorization header should be present")
            val authHeader = headers["Authorization"]!!
            assertTrue(authHeader.startsWith("Basic "), "Should use Basic auth scheme")
            // Decode and verify the base64 credentials
            @OptIn(ExperimentalEncodingApi::class)
            val decoded = Base64.decode(authHeader.substringAfter("Basic ")).decodeToString()
            assertEquals("user:pass", decoded, "Basic auth credentials should be base64-encoded")
        }

    @Test
    fun testCustomHeaderSetsCorrectHeader() =
        runTest {
            val headers = mutableMapOf<String, String>()
            val fetcher = fetcherWithHeaderCapture(headers)
            val source = ConfigSource(
                url = "https://example.com/config.yaml",
                auth = HttpAuth.CustomHeader("X-Token", "abc")
            )

            fetcher.fetch(source)

            assertTrue(headers.containsKey("X-Token"), "Custom header should be present")
            assertEquals("abc", headers["X-Token"], "Custom header value incorrect")
        }

    @Test
    fun testHttpWithAuthThrowsE0013() =
        runTest {
            val headers = mutableMapOf<String, String>()
            val fetcher = fetcherWithHeaderCapture(headers)
            val source = ConfigSource(
                url = "http://example.com/config.yaml",
                auth = HttpAuth.Bearer("mytoken")
            )

            val ex = assertFailsWith<ConfigException> { fetcher.fetch(source) }
            assertTrue(ex.message!!.contains("[E0013]"), "Should contain E0013 error code")
            assertTrue(
                ex.message!!.contains("HTTPS") || ex.message!!.contains("plain HTTP"),
                "Error message should mention HTTPS requirement"
            )
        }

    @Test
    fun testNoAuthAllowsHttp() =
        runTest {
            val headers = mutableMapOf<String, String>()
            val fetcher = fetcherWithHeaderCapture(headers)
            val source = ConfigSource(
                url = "http://example.com/config.yaml",
                auth = null
            )

            // Should succeed without throwing
            val content = fetcher.fetch(source)
            assertEquals("# config", content, "Should successfully fetch content")
        }
}
