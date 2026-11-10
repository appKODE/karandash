package io.karandash.platforms.bitbucket

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

class BitbucketApprovalAndBlameTest {
    @Test
    fun `approve posts to the approve endpoint when the bot has not approved yet`() =
        runTest {
            val server = RecordingMockServer(routes(botApproved = false))

            service(server).approve("ws/demo", "7")

            assertEquals(listOf("GET /2.0/user", "GET $PR", "POST $PR/approve"), calls(server))
        }

    @Test
    fun `approve is a no-op when the bot already approved`() =
        runTest {
            val server = RecordingMockServer(routes(botApproved = true))

            service(server).approve("ws/demo", "7")

            assertEquals(listOf("GET /2.0/user", "GET $PR"), calls(server))
        }

    @Test
    fun `unapprove deletes the approval of the bot`() =
        runTest {
            val server = RecordingMockServer(routes(botApproved = true))

            service(server).unapprove("ws/demo", "7")

            assertEquals(listOf("GET /2.0/user", "GET $PR", "DELETE $PR/approve"), calls(server))
        }

    @Test
    fun `fetchFileBlame reads the file history then each commit`() =
        runTest {
            val server = RecordingMockServer(routes(botApproved = false))

            val blame = service(server).fetchFileBlame("ws/demo", "src/a.kt", "main")

            assertEquals(listOf("c1"), blame.map { it.commit.sha })
            assertEquals(listOf("GET $REPO/filehistory/main/src/a.kt", "GET $REPO/commit/c1"), calls(server))
        }

    @Test
    fun `fetchMergeRequestByUrl fetches the pull request the url names`() =
        runTest {
            val server = RecordingMockServer(routes(botApproved = false))

            val mr = service(server).fetchMergeRequestByUrl("https://bitbucket.org/ws/demo/pull-requests/7")

            assertEquals("Add key", mr.title)
        }

    private suspend fun service(server: RecordingMockServer) =
        BitbucketPlatformService(server.engine).also {
            it.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t")))))
        }

    private suspend fun calls(server: RecordingMockServer) = server.requests().map { "${it.method.value} ${it.url.encodedPath}" }

    private fun routes(botApproved: Boolean): List<MockRoute> =
        listOf(
            route(HttpMethod.Get, "/2.0/user", BOT),
            route(HttpMethod.Post, "$PR/approve", """{"approved":true}"""),
            route(HttpMethod.Delete, "$PR/approve", ""),
            route(HttpMethod.Get, "/filehistory/main/src/a.kt", """{"values":[{"commit":{"hash":"c1"},"path":"src/a.kt"}]}"""),
            route(HttpMethod.Get, "/commit/c1", """{"hash":"c1","date":"$DATE","message":"m","author":{"raw":"Dev <d@x>"}}"""),
            route(HttpMethod.Get, "$PR/diff", ""),
            route(HttpMethod.Get, "$PR/diffstat", """{"values":[]}"""),
            route(HttpMethod.Get, "$PR/commits", """{"values":[]}"""),
            route(HttpMethod.Get, "$PR/statuses", """{"values":[]}"""),
            route(
                HttpMethod.Get,
                PR,
                """{"id":7,"title":"Add key","description":"","state":"OPEN","author":$BOT,""" +
                    """"source":{"branch":{"name":"f"},"commit":{"hash":"h"},"repository":$REPO_JSON},""" +
                    """"destination":{"branch":{"name":"main"},"commit":{"hash":"b"},"repository":$REPO_JSON},""" +
                    """"created_on":"$DATE","updated_on":"$DATE","reviewers":[],""" +
                    """"participants":[{"user":$BOT,"role":"REVIEWER","approved":$botApproved}]}""",
            ),
        )

    private companion object {
        const val REPO = "/2.0/repositories/ws/demo"
        const val PR = "$REPO/pullrequests/7"
        const val DATE = "2026-10-01T10:00:00Z"
        const val BOT = """{"uuid":"{bot}","display_name":"Bot","nickname":"bot"}"""
        const val REPO_JSON = """{"uuid":"{r1}","name":"demo","full_name":"ws/demo"}"""
    }
}
