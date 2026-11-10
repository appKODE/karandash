package io.karandash.platforms.gitlab

import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.route
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GitLab summary lookup, merge request id parsing, draft prefix, live helpers. */
class GitLabRegressionTest {
    private suspend fun service(server: RecordingMockServer): GitLabPlatformService {
        val service = GitLabPlatformService(server.engine)
        val config = JsonObject(mapOf("url" to JsonPrimitive(URL), "token" to JsonPrimitive("t")))
        assertTrue(service.initialize(TestPluginContext(config)))
        return service
    }

    @Test
    fun `parseDiffStats feeds the GitLab file change counts`() {
        val mr = GitLabAdapter.toMergeRequest(
            GitLabMergeRequest(
                id = 1,
                iid = 1,
                title = "t",
                description = null,
                sourceBranch = "f",
                targetBranch = "main",
                author = GitLabUser(1, "u", "U", null),
                state = "opened",
            ),
            emptyList(),
            listOf(GitLabDiff("a.kt", "a.kt", false, false, false, "@@ -1,2 +1,3 @@\n ctx\n-old\n+new\n+more")),
        )
        assertEquals(2 to 1, mr.changes.single().let { it.additions to it.deletions })
    }

    @Test
    fun `supportedEnvVars lists exactly the env names credentials resolution reads`() {
        val read = mutableSetOf<String>()
        val lookup = { name: String -> read.add(name).let { null } }
        GitLabCredentials.resolve(JsonObject(mapOf("token" to JsonPrimitive("t"))), lookup)
        GitLabCredentials.resolve(JsonObject(emptyMap()), lookup)
        assertEquals(GitLabCredentials.supportedEnvVars(), read)
    }

    private suspend fun postedPosition(
        newLine: Int?,
        oldLine: Int?,
    ): String {
        val server = RecordingMockServer(listOf(route(HttpMethod.Post, "$MR/discussions", """{"id":"d1","notes":[${note(11, "x")}]}""")))
        val client =
            GitLabClient(
                baseUrl = URL,
                token = "t",
                httpClient = HttpClient(server.engine) {
                    install(ContentNegotiation) { json(KarandashJson.Default) }
                }
            )
        GitLabDiscussionsClient(client).createDiffDiscussion("g/p", 7, "b", "base", "start", "head", "a.kt", newLine, oldLine = oldLine)
        return (server.requests().single().body as TextContent).text
    }

    @Test
    fun `a deletion anchors on old_line without a new_line`() =
        runTest {
            val body = postedPosition(newLine = null, oldLine = 4)
            assertTrue("\"old_line\":4" in body, body)
            assertTrue("new_line" !in body, body)
        }

    @Test
    fun `an added line keeps new_line only`() =
        runTest {
            val body = postedPosition(newLine = 4, oldLine = null)
            assertTrue("\"new_line\":4" in body, body)
            assertTrue("old_line" !in body, body)
        }

    @Test
    fun `a 400 line_code answer fails the inline post so the finding stays in the summary note`() =
        runTest {
            val rejected = """{"message":"400 Bad request - Note {:line_code=>[\"can't be blank\", \"must be a valid line code\"]}"}"""
            val server = RecordingMockServer(listOf(route(HttpMethod.Post, "$MR/discussions", rejected, HttpStatusCode.BadRequest)))
            val client =
                GitLabClient(
                    baseUrl = URL,
                    token = "t",
                    httpClient = HttpClient(server.engine) { install(ContentNegotiation) { json(KarandashJson.Default) } },
                )

            val error = assertFails {
                GitLabDiscussionsClient(client).createDiffDiscussion("g/p", 7, "b", "base", "start", "head", "a.kt", 3)
            }
            assertTrue("line_code" in error.message.orEmpty() || "400" in error.message.orEmpty(), error.message)
        }

    @Test
    fun `a position with no line is refused before any request`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { postedPosition(newLine = null, oldLine = null) }
        }

    private fun note(
        id: Int,
        body: String,
        system: Boolean = false,
    ) = """{"id":$id,"body":${JsonPrimitive(body)},"author":$USER,"system":$system,"created_at":"2026-10-01T10:00:00Z"}"""

    private fun notesServer(vararg notes: String) =
        RecordingMockServer(listOf(route(HttpMethod.Get, "$MR/notes", notes.joinToString(prefix = "[", postfix = "]"))))

    @Test
    fun `the summary note wins over a human reply quoting the marker`() =
        runTest {
            val server = notesServer(note(1, "> $MARKER\nI disagree"), note(2, "$MARKER\nsummary"))
            assertEquals("2", service(server).findExistingComment("g/p", "7", MARKER))
        }

    @Test
    fun `a v1_41_1 summary note with the marker below a heading is still found`() =
        runTest {
            val server = notesServer(note(5, "## Karandash review\n\n$MARKER\n\n3 findings"))
            assertEquals("5", service(server).findExistingComment("g/p", "7", MARKER))
        }

    @Test
    fun `a system note mentioning the marker is not a summary`() =
        runTest {
            val server = notesServer(note(1, "edited $MARKER", system = true))
            assertNull(service(server).findExistingComment("g/p", "7", MARKER))
        }

    @Test
    fun `a non-numeric iid is a clear error`() =
        runTest {
            val error = assertFailsWith<IllegalArgumentException> {
                service(notesServer()).findExistingComment("g/p", "abc", MARKER)
            }
            assertEquals("Invalid merge request ID: abc", error.message)
        }

    @Test
    fun `marking ready strips every draft prefix GitLab recognises`() =
        runTest {
            for (title in listOf("Draft: x", "[Draft] x", "(Draft) x", "WIP: x", "draft: x")) {
                val server = RecordingMockServer(
                    listOf(
                        route(HttpMethod.Get, MR, mr(title)),
                        route(HttpMethod.Put, MR, mr("x")),
                    ),
                )
                service(server).setDraft("g/p", "7", false)
                val put = server.requests().single { it.method == HttpMethod.Put }
                assertEquals("""{"title":"x"}""", (put.body as TextContent).text, title)
            }
        }

    private fun mr(title: String) =
        """{"id":1,"iid":7,"title":${JsonPrimitive(title)},"description":null,"source_branch":"f","target_branch":"main",""" +
            """"author":$USER,"state":"opened"}"""

    private companion object {
        const val URL = "https://gitlab.example.com"
        const val MR = "/api/v4/projects/g%2Fp/merge_requests/7"
        const val USER = """{"id":1,"username":"bot","name":"bot"}"""
        const val MARKER = "<!-- s -->"
    }
}
