package io.karandash.platforms.gitlab

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GitLabDismissedCommentsTest {
    private fun note(
        id: Int,
        body: String,
        resolvedBy: String?,
    ): String {
        val resolver = resolvedBy?.let { """{"id":2,"username":"$it","name":"$it"}""" } ?: "null"
        return """{"id":$id,"body":"$body","author":{"id":1,"username":"bot","name":"Bot"},""" +
            """"resolvable":true,"resolved":${resolvedBy != null},"resolved_by":$resolver}"""
    }

    @Test
    fun `marked notes resolved by someone other than their author count as dismissed`() =
        runTest {
            val notes = listOf(
                note(1, "a <!-- kd -->", "alice"),
                note(2, "b <!-- kd -->", "bot"),
                note(3, "c <!-- kd -->", null),
                note(4, "human", "alice"),
            )
            val body = notes.joinToString(",", "[", "]") { """{"id":"d","notes":[$it]}""" }
            val engine = MockEngine { request ->
                val reply = if (request.url.encodedPath.endsWith("/award_emoji")) "[]" else body
                respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val service = GitLabPlatformService()
            service.client.rebuild("test") { GitLabClient(token = "t", httpClient = HttpClient(engine), responseCache = null) }

            assertEquals(listOf("a <!-- kd -->"), service.listDismissedInlineComments("g/p", "7", "<!-- kd -->"))
        }
}
