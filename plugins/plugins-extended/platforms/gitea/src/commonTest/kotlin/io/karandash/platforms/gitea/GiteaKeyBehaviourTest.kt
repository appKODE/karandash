package io.karandash.platforms.gitea

import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each Gitea key changes the request the service sends. */
class GiteaKeyBehaviourTest {
    private suspend fun firstRequest(config: Map<String, String>): HttpRequestData {
        val server = RecordingMockServer(emptyList())
        val service = GiteaPlatformService(server.engine)
        service.initialize(TestPluginContext(JsonObject(config.mapValues { JsonPrimitive(it.value) })))
        runCatching { service.findExistingComment("owner/repo", "7", "marker") }
        return server.requests().first()
    }

    @Test
    fun `url sends API requests to the configured host`() =
        runTest {
            val request = firstRequest(mapOf("url" to "https://gitea.example.com", "token" to "t"))
            assertEquals("gitea.example.com", request.url.host)
            assertTrue(request.url.encodedPath.startsWith("/api/v1/repos/owner/repo"), request.url.encodedPath)
        }

    @Test
    fun `token is sent in the token authorization header`() =
        runTest {
            val request = firstRequest(mapOf("url" to "https://gitea.example.com", "token" to "secret-tok"))
            assertEquals("token secret-tok", request.headers[HttpHeaders.Authorization])
        }
}
