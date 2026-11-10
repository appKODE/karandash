package io.karandash.platforms.bitbucket

import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each Bitbucket key changes the request the service sends. */
class BitbucketKeyBehaviourTest {
    private suspend fun firstRequest(config: Map<String, String>): HttpRequestData {
        val server = RecordingMockServer(emptyList())
        val service = BitbucketPlatformService(server.engine)
        service.initialize(TestPluginContext(JsonObject(config.mapValues { JsonPrimitive(it.value) })))
        runCatching { service.findExistingComment("ws/repo", "7", "marker") }
        return server.requests().first()
    }

    @Test
    fun `url sends API requests to the configured host`() =
        runTest {
            val request = firstRequest(mapOf("token" to "t", "url" to "https://bb.example.com/rest/2.0"))
            assertEquals("bb.example.com", request.url.host)
            assertEquals("/rest/2.0/repositories/ws/repo/pullrequests/7/comments", request.url.encodedPath)
        }

    @Test
    fun `token is sent as a bearer credential`() =
        runTest {
            val request = firstRequest(mapOf("token" to "secret-tok"))
            assertEquals("api.bitbucket.org", request.url.host)
            assertEquals("Bearer secret-tok", request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `username turns the token into a basic app password credential`() =
        runTest {
            val request = firstRequest(mapOf("token" to "app-pass", "username" to "alice"))
            assertEquals("Basic " + Base64.encode("alice:app-pass".encodeToByteArray()), request.headers[HttpHeaders.Authorization])
        }
}
