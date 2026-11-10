@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.azuredevops

import io.karandash.api.platform.PlatformOperation
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Azure DevOps resolves an earlier run's inline threads (status `fixed`) instead of closing them for a re-post. */
class AzureDevOpsInlineCleanupTest {
    private val writes = mutableListOf<String>()

    private fun context(path: String) =
        """"threadContext":{"filePath":"/$path","rightFileStart":{"line":1,"offset":1},"rightFileEnd":{"line":1,"offset":1}}"""

    private suspend fun service(): AzureDevOpsPlatformService {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                val threads = listOf(
                    """{"id":1,"status":"active","comments":[{"id":1,"content":"old <!-- kd -->"}],${context("a.kt")}}""",
                    """{"id":2,"status":"active","comments":[{"id":1,"content":"<!-- sum --> summary"}]}""",
                    """{"id":3,"status":"active","comments":[{"id":1,"content":"human"}],${context("a.kt")}}""",
                    """{"id":4,"status":"active","comments":[{"id":1,"content":"cached <!-- kd -->"}],${context("b.kt")}}""",
                    """{"id":5,"status":"fixed","comments":[{"id":1,"content":"done <!-- kd -->"}],${context("a.kt")}}""",
                    """{"id":6,"status":"wontFix","comments":[{"id":1,"content":"dismissed <!-- kd -->"}],${context("a.kt")}}""",
                    """{"id":7,"status":"active","comments":[{"id":1,"content":"<!-- sum --> again"}]}""",
                    """{"id":8,"status":"active","comments":[{"id":1,"content":"> quoting <!-- sum --> in a human reply"}]}""",
                )
                respond(threads.joinToString(",", """{"value":[""", "]}"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                writes += "${request.method.value} ${request.url.encodedPath} " + ((request.body as? TextContent)?.text ?: "")
                respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = AzureDevOpsPlatformService()
        service.commentClient.rebuild("test") { AzureDevOpsClient(baseUrl = "https://dev.azure.test", httpClient = http) }
        return service
    }

    @Test
    fun `resolveMarkedInlineCommentsForFiles marks active marked threads on re-reviewed files fixed`() =
        runTest {
            val count = service().resolveMarkedInlineCommentsForFiles("org/proj/repo", "7", "<!-- kd -->", setOf("a.kt"))

            assertEquals(1, count)
            assertEquals(listOf("""PATCH /org/proj/_apis/git/repositories/repo/pullrequests/7/threads/1 {"status":"fixed"}"""), writes)
        }

    @Test
    fun `resolveMarkedInlineComments resolves every active marked inline thread and leaves dismissed ones`() =
        runTest {
            assertEquals(2, service().resolveMarkedInlineComments("org/proj/repo", "7", "<!-- kd -->"))
            assertEquals(listOf("threads/1", "threads/4"), writes.map { it.split(" ")[1].substringAfter("7/") })
        }

    @Test
    fun `resolveThread replies with the note then marks the thread fixed`() =
        runTest {
            val service = service()

            assertEquals(true, service.resolveThread("org/proj/repo", "7", "3:1", "done").isSuccess)
            assertEquals(false, service.resolveThread("org/proj/repo", "7", "x", null).isSuccess)

            val prefix = "/org/proj/_apis/git/repositories/repo/pullrequests/7/threads/3"
            assertEquals(
                listOf(
                    """POST $prefix/comments {"content":"done","parentCommentId":1,"commentType":1}""",
                    """PATCH $prefix {"status":"fixed"}""",
                ),
                writes,
            )
        }

    @Test
    fun `azure devops advertises thread resolution`() =
        runTest {
            assertEquals(true, service().supports(PlatformOperation.RESOLVE_DISCUSSIONS))
        }

    @Test
    fun `findAllMatchingComments returns every summary copy and deleteComment removes one`() =
        runTest {
            val service = service()

            assertEquals(listOf("2:1", "7:1"), service.findAllMatchingComments("org/proj/repo", "7", "<!-- sum -->"))
            service.deleteComment("org/proj/repo", "7", "7:1")
            assertEquals(listOf("DELETE /org/proj/_apis/git/repositories/repo/pullrequests/7/threads/7/comments/1 "), writes)
        }

    @Test
    fun `listDismissedInlineComments returns marked inline threads closed as won't fix or by design`() =
        runTest {
            val context = """"threadContext":{"filePath":"/a.kt","rightFileStart":{"line":1,"offset":1},""" +
                """"rightFileEnd":{"line":1,"offset":1}}"""
            val threads = listOf(
                """{"id":1,"status":"wontFix","comments":[{"content":"a <!-- kd -->"}],$context}""",
                """{"id":2,"status":"byDesign","comments":[{"content":"b <!-- kd -->"}],$context}""",
                """{"id":3,"status":"fixed","comments":[{"content":"c <!-- kd -->"}],$context}""",
                """{"id":4,"status":"wontFix","comments":[{"content":"summary <!-- kd -->"}]}""",
                """{"id":5,"status":"wontFix","comments":[{"content":"human"}],$context}""",
            )
            val engine = MockEngine {
                respond(threads.joinToString(",", """{"value":[""", "]}"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
            val service = AzureDevOpsPlatformService()
            service.commentClient.rebuild("test") { AzureDevOpsClient(baseUrl = "https://dev.azure.test", httpClient = http) }

            assertEquals(
                listOf("a <!-- kd -->", "b <!-- kd -->"),
                service.listDismissedInlineComments("org/proj/repo", "7", "<!-- kd -->"),
            )
        }
}
