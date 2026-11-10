@file:OptIn(ExperimentalKarandashApi::class)

package io.karandash.platforms.bitbucket

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.platform.MergeRequestField
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
import kotlin.test.assertTrue

/** Bitbucket draft flag, token env names, diff failure, file history commits. */
class BitbucketRegressionTest {
    private suspend fun service(
        server: RecordingMockServer,
        context: TestPluginContext = TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t")))),
    ) = BitbucketPlatformService(server.engine).also { it.initialize(context) }

    @Test
    fun `the boolean draft flag marks the pull request as draft`() =
        runTest {
            val mr = service(RecordingMockServer(routes(draft = true))).fetchMergeRequest("ws/demo", "7")
            assertTrue(mr.draft)
        }

    @Test
    fun `CI token falls back to BITBUCKET_TOKEN`() =
        runTest {
            val env = mapOf("BITBUCKET_BUILD_NUMBER" to "1", "BITBUCKET_TOKEN" to "tok")
            val ci = BitbucketPlatformService().detectCiEnvironment { env[it] }
            assertEquals("tok", ci?.token)
        }

    @Test
    fun `credentials accept BITBUCKET_ACCESS_TOKEN`() {
        val env = mapOf("BITBUCKET_ACCESS_TOKEN" to "tok")
        assertEquals("tok", BitbucketCredentials.resolve(JsonObject(emptyMap())) { env[it] }.token)
    }

    @Test
    fun `a failed diff download is warned about`() =
        runTest {
            val context = TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))
            val server = RecordingMockServer(routes(draft = false, diffStatus = HttpStatusCode.Forbidden))

            service(server, context).fetchMergeRequest("ws/demo", "7")

            assertTrue(context.warnings.any { it.contains("diff") }, context.warnings.toString())
        }

    @Test
    fun `labels are declared unavailable`() {
        assertTrue(MergeRequestField.LABELS in BitbucketPlatformService().unavailableMergeRequestFields())
    }

    @Test
    fun `file history that carries the commit needs no commit detail call`() =
        runTest {
            val server = RecordingMockServer(routes(draft = false))

            val blame = service(server).fetchFileBlame("ws/demo", "src/a.kt", "main")

            assertEquals(listOf("c1"), blame.map { it.commit.sha })
            assertEquals(
                "Dev",
                blame
                    .single()
                    .commit.author.name
            )
            assertEquals(
                listOf("GET $REPO/filehistory/main/src/a.kt"),
                server.requests().map { "${it.method.value} ${it.url.encodedPath}" }
            )
        }

    private fun routes(
        draft: Boolean,
        diffStatus: HttpStatusCode = HttpStatusCode.OK,
    ): List<MockRoute> =
        listOf(
            route(
                HttpMethod.Get,
                "/filehistory/main/src/a.kt",
                """{"values":[{"commit":{"hash":"c1","date":"$DATE","message":"m","author":{"raw":"Dev <d@x>"}},"path":"src/a.kt"}]}""",
            ),
            route(HttpMethod.Get, "$PR/diff", "", diffStatus),
            route(HttpMethod.Get, "$PR/diffstat", """{"values":[]}"""),
            route(HttpMethod.Get, "$PR/commits", """{"values":[]}"""),
            route(HttpMethod.Get, "$PR/statuses", """{"values":[]}"""),
            route(
                HttpMethod.Get,
                PR,
                """{"id":7,"title":"Add key","description":"","state":"OPEN","draft":$draft,"author":$BOT,""" +
                    """"source":{"branch":{"name":"f"},"commit":{"hash":"h"},"repository":$REPO_JSON},""" +
                    """"destination":{"branch":{"name":"main"},"commit":{"hash":"b"},"repository":$REPO_JSON},""" +
                    """"created_on":"$DATE","updated_on":"$DATE","reviewers":[],"participants":[]}""",
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
