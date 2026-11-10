package io.karandash.platforms.gerrit

import io.karandash.api.exception.ProviderApiException
import io.karandash.testfixtures.MockRoute
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.route
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GerritStatusChecksTest {
    @Test
    fun `setDraft fails when Gerrit rejects the wip call`() =
        runTest {
            val server = RecordingMockServer(routes(write = HttpStatusCode.Forbidden))

            val error = assertFailsWith<ProviderApiException> { service(server).setDraft("demo", "7", true) }

            assertEquals(403, error.statusCode)
        }

    @Test
    fun `setDraft on a change already in that state is a no-op`() =
        runTest {
            val server = RecordingMockServer(routes(write = HttpStatusCode.Conflict))

            service(server).setDraft("demo", "7", true)
            service(server).setDraft("demo", "7", false)
        }

    @Test
    fun `updateTitle fails when Gerrit rejects the new commit message`() =
        runTest {
            val server = RecordingMockServer(routes(write = HttpStatusCode.Forbidden))

            val error = assertFailsWith<ProviderApiException> { service(server).updateTitle("demo", "7", "New subject") }

            assertEquals(403, error.statusCode)
        }

    @Test
    fun `updateTitle with an unchanged commit message is a no-op`() =
        runTest {
            val server = RecordingMockServer(routes(write = HttpStatusCode.Conflict))

            service(server).updateTitle("demo", "7", "Old subject")
        }

    @Test
    fun `updateDescription fails when the commit message cannot be read`() =
        runTest {
            val server = RecordingMockServer(routes(write = HttpStatusCode.OK, message = HttpStatusCode.NotFound))

            val error = assertFailsWith<ProviderApiException> { service(server).updateDescription("demo", "7", "New body") }

            assertEquals(404, error.statusCode)
        }

    @Test
    fun `fetchFileBlame is unsupported and sends no request`() =
        runTest {
            val server = RecordingMockServer(routes(write = HttpStatusCode.OK))
            val gerrit = service(server)
            val before = server.requests().size

            assertEquals(emptyList(), gerrit.fetchFileBlame("demo", "src/A.kt", "main"))
            assertEquals(before, server.requests().size)
        }

    private suspend fun service(server: RecordingMockServer) =
        GerritPlatformService(server.engine).also {
            val config = mapOf("url" to "https://gerrit.example.com", "username" to "bot", "token" to "t")
            it.initialize(TestPluginContext(JsonObject(config.mapValues { e -> JsonPrimitive(e.value) })))
        }

    private fun routes(
        write: HttpStatusCode,
        message: HttpStatusCode = HttpStatusCode.OK,
    ): List<MockRoute> =
        listOf(
            route(HttpMethod.Get, "/a/accounts/self", """$XSSI{"_account_id":1000}"""),
            route(
                HttpMethod.Get,
                "/a/changes/7/message",
                """$XSSI{"subject":"Old subject","full_message":"Old subject\n\nBody\n\nChange-Id: I1\n"}""",
                status = message,
            ),
            MockRoute({ it.method != HttpMethod.Get }, "conflict or denied", status = write),
        )

    private companion object {
        const val XSSI = ")]}'\n"
    }
}
