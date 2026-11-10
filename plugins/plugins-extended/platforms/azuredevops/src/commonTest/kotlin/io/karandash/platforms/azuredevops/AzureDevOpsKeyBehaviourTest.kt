package io.karandash.platforms.azuredevops

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

/** Azure DevOps keys that change the request: token (Basic auth), url (host) and organization (short project ids). */
class AzureDevOpsKeyBehaviourTest {
    private suspend fun firstRequest(
        config: Map<String, String>,
        projectId: String = "org/proj/repo",
    ): HttpRequestData {
        val server = RecordingMockServer(emptyList())
        val service = AzureDevOpsPlatformService(server.engine)
        service.initialize(TestPluginContext(JsonObject(config.mapValues { JsonPrimitive(it.value) })))
        runCatching { service.findExistingComment(projectId, "7", "marker") }
        return server.requests().first()
    }

    @Test
    fun `token is sent as a basic credential with an empty user name`() =
        runTest {
            val request = firstRequest(mapOf("token" to "pat-123"))
            assertEquals("Basic " + Base64.encode(":pat-123".encodeToByteArray()), request.headers[HttpHeaders.Authorization])
            assertEquals("dev.azure.com", request.url.host)
        }

    @Test
    fun `url sends API requests to the configured server`() =
        runTest {
            val request = firstRequest(mapOf("token" to "t", "url" to "https://tfs.corp.test/tfs"))
            assertEquals("tfs.corp.test", request.url.host)
            assertEquals(true, request.url.encodedPath.startsWith("/tfs/org/proj/"), request.url.encodedPath)
        }

    @Test
    fun `organization completes a project and repository id`() =
        runTest {
            val request = firstRequest(mapOf("token" to "t", "organization" to "acme"), projectId = "proj/repo")
            assertEquals(true, request.url.encodedPath.startsWith("/acme/proj/"), request.url.encodedPath)
        }
}
