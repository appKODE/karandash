package io.karandash.platforms.gerrit

import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each Gerrit key changes the request the service sends. */
class GerritKeyBehaviourTest {
    private suspend fun firstRequest(config: Map<String, String>): HttpRequestData {
        val server = RecordingMockServer(emptyList())
        val service = GerritPlatformService(server.engine)
        service.initialize(TestPluginContext(JsonObject(config.mapValues { JsonPrimitive(it.value) })))
        runCatching { service.findExistingComment("proj", "7", "marker") }
        return server.requests().first()
    }

    @Test
    fun `url sends API requests to the configured host`() =
        runTest {
            val request = firstRequest(mapOf("url" to "https://gerrit.example.com"))
            assertEquals("gerrit.example.com", request.url.host)
        }

    @Test
    fun `username and token are sent as basic credentials under the authenticated path`() =
        runTest {
            val request = firstRequest(mapOf("url" to "https://gerrit.example.com", "username" to "bot", "token" to "http-pass"))
            assertEquals("Basic " + Base64.encode("bot:http-pass".encodeToByteArray()), request.headers[HttpHeaders.Authorization])
            assertTrue(request.url.encodedPath.startsWith("/a/"), request.url.encodedPath)
        }

    @Test
    fun `without username and token requests are anonymous`() =
        runTest {
            val request = firstRequest(mapOf("url" to "https://gerrit.example.com"))
            assertNull(request.headers[HttpHeaders.Authorization])
            assertTrue(!request.url.encodedPath.startsWith("/a/"), request.url.encodedPath)
        }

    @Test
    fun `notify sets who Gerrit emails about a review and defaults to the owner`() =
        runTest {
            assertTrue("\"notify\":\"OWNER\"" in reviewBody(emptyMap()))
            assertTrue("\"notify\":\"NONE\"" in reviewBody(mapOf("notify" to "NONE")))
        }

    private suspend fun reviewBody(extra: Map<String, String>): String {
        val server = RecordingMockServer(emptyList())
        val service = GerritPlatformService(server.engine)
        val config = mapOf("url" to "https://gerrit.example.com", "username" to "bot", "token" to "t") + extra
        service.initialize(TestPluginContext(JsonObject(config.mapValues { JsonPrimitive(it.value) })))
        runCatching { service.postComment("proj", "7", "hi") }
        val review = server.requests().first { it.url.encodedPath.endsWith("/review") }
        return (review.body as TextContent).text
    }
}
