package io.karandash.platforms.gitlab

import io.karandash.testfixtures.MockRoute
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.route
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class GitLabMarkedThreadsAndBlameTest {
    @Test
    fun `resolveMarkedInlineComments resolves only open threads carrying the marker`() =
        runTest {
            val server = RecordingMockServer(routes())

            val resolved = service(server).resolveMarkedInlineComments("g/p", "7", MARKER)

            assertEquals(2, resolved)
            assertEquals(listOf("PUT $MR/discussions/d1", "PUT $MR/discussions/d2"), writes(server))
        }

    @Test
    fun `resolveMarkedInlineCommentsForFiles skips threads on other files`() =
        runTest {
            val server = RecordingMockServer(routes())

            val resolved = service(server).resolveMarkedInlineCommentsForFiles("g/p", "7", MARKER, setOf("a.kt"))

            assertEquals(1, resolved)
            assertEquals(listOf("PUT $MR/discussions/d1"), writes(server))
        }

    @Test
    fun `deleteMarkedInlineComments deletes every marked note thread by thread`() =
        runTest {
            val server = RecordingMockServer(routes())

            val deleted = service(server).deleteMarkedInlineComments("g/p", "7", MARKER)

            assertEquals(3, deleted)
            assertEquals(
                listOf("DELETE $MR/discussions/d1/notes/1", "DELETE $MR/discussions/d2/notes/2", "DELETE $MR/discussions/d3/notes/3"),
                writes(server),
            )
        }

    @Test
    fun `deleteMarkedInlineComments with the note strategy deletes marked MR notes`() =
        runTest {
            val server = RecordingMockServer(routes())

            val deleted = service(server, "deleteInlineStrategy" to "note").deleteMarkedInlineComments("g/p", "7", MARKER)

            assertEquals(1, deleted)
            assertEquals(listOf("DELETE $MR/notes/31"), writes(server))
        }

    @Test
    fun `fetchFileBlame reads the blame of the file at the ref`() =
        runTest {
            val server = RecordingMockServer(routes())

            val blame = service(server).fetchFileBlame("g/p", "src/a.kt", "main")

            assertEquals(listOf("c1"), blame.map { it.commit.sha })
            val request = server.requests().single()
            assertEquals("/api/v4/projects/g%2Fp/repository/files/src%2Fa.kt/blame", request.url.encodedPath)
            assertEquals("main", request.url.parameters["ref"])
        }

    private suspend fun service(
        server: RecordingMockServer,
        vararg extra: Pair<String, String>,
    ) = GitLabPlatformService(server.engine).also {
        val config = mapOf("url" to "https://gitlab.example.com", "token" to "t", *extra).mapValues { e -> JsonPrimitive(e.value) }
        it.initialize(TestPluginContext(JsonObject(config)))
    }

    private suspend fun writes(server: RecordingMockServer) =
        server.requests().filter { it.method != HttpMethod.Get }.map { "${it.method.value} ${it.url.encodedPath}" }

    private fun routes(): List<MockRoute> =
        listOf(
            route(
                HttpMethod.Get,
                "$MR/discussions",
                "[" +
                    discussion("d1", note(1, "$MARKER x", resolved = false, path = "a.kt")) + "," +
                    discussion("d2", note(2, "$MARKER y", resolved = false, path = "b.kt")) + "," +
                    discussion("d3", note(3, "$MARKER z", resolved = true, path = "a.kt")) + "," +
                    discussion("d4", note(4, "human", resolved = false, path = "a.kt")) +
                    "]",
            ),
            route(HttpMethod.Get, "$MR/notes", """[{"id":31,"body":"$MARKER s"},{"id":32,"body":"other"}]"""),
            MockRoute({ it.method == HttpMethod.Put }, """{"id":"d","notes":[]}"""),
            MockRoute({ it.method == HttpMethod.Delete }, ""),
            route(
                HttpMethod.Get,
                "/blame",
                """[{"lines":["a"],"commit":{"id":"c1","message":"m","author_name":"Dev",""" +
                    """"author_email":"d@x","authored_date":"2026-10-01T10:00:00Z"}}]""",
            ),
        )

    private fun discussion(
        id: String,
        note: String,
    ) = """{"id":"$id","notes":[$note]}"""

    private fun note(
        id: Int,
        body: String,
        resolved: Boolean,
        path: String,
    ) = """{"id":$id,"body":"$body","resolvable":true,"resolved":$resolved,"position":{"new_path":"$path","old_path":"$path"}}"""

    private companion object {
        const val MR = "/api/v4/projects/g%2Fp/merge_requests/7"
        const val MARKER = "<!-- k -->"
    }
}
