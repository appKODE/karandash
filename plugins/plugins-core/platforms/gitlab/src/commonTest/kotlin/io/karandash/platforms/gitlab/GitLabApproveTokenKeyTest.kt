package io.karandash.platforms.gitlab

import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.route
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Behaviour of the gitlab `approveToken` key through the platform service: it authenticates approvals only. */
class GitLabApproveTokenKeyTest {
    private fun server() =
        RecordingMockServer(
            listOf(
                route(HttpMethod.Get, "/api/v4/user", """{"id":1,"username":"me","name":"Me"}"""),
                route(HttpMethod.Post, "/approve", "{}"),
            ),
        )

    private suspend fun service(
        server: RecordingMockServer,
        vararg extra: Pair<String, String>,
    ): GitLabPlatformService {
        val service = GitLabPlatformService(server.engine)
        val entries = mapOf("url" to "https://gitlab.example.com", "token" to "project-token") + extra
        val config = JsonObject(entries.mapValues { JsonPrimitive(it.value) })
        assertTrue(service.initialize(TestPluginContext(config)))
        return service
    }

    @Test
    fun `approveToken authenticates the approve call instead of the project token`() =
        runTest {
            val server = server()
            service(server, "approveToken" to "user-pat").approve("group/project", "7")

            val post = server.requests().single { it.method == HttpMethod.Post }
            assertEquals("user-pat", post.headers["PRIVATE-TOKEN"])
        }

    @Test
    fun `without approveToken the approve call is skipped and nothing is sent`() =
        runTest {
            val server = server()
            service(server).approve("group/project", "7")

            assertEquals(emptyList(), server.requests().map { it.method })
        }
}
