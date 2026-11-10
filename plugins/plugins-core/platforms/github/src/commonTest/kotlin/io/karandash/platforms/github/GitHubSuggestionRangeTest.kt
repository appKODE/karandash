package io.karandash.platforms.github

import io.karandash.api.finding.InlineCommentData
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A suggestion over a line range is posted with `start_line`/`line` on the new-file side. */
class GitHubSuggestionRangeTest {
    private val sent = mutableListOf<JsonObject>()

    private fun client(): GitHubClient {
        val engine = MockEngine { request ->
            sent += KarandashJson.Default.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond("""{"id": 1}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return GitHubClient(baseUrl = "https://api.github.com", token = "t", httpClient = http, responseCache = null)
    }

    private fun post(startLine: Int?) =
        runTest {
            client().createPullRequestReviewComment("o", "r", 1, "```suggestion\nx\n```", "sha", "A.kt", 12, startLine)
        }

    @Test
    fun `range comment sends start_line and both sides`() {
        post(startLine = 10)

        val body = sent.single()
        assertEquals("10", body.getValue("start_line").jsonPrimitive.content)
        assertEquals("12", body.getValue("line").jsonPrimitive.content)
        assertEquals("RIGHT", body.getValue("start_side").jsonPrimitive.content)
        assertEquals("RIGHT", body.getValue("side").jsonPrimitive.content)
    }

    @Test
    fun `a batch is one COMMENT review carrying every comment`() =
        runTest {
            val comments = listOf(InlineCommentData("a.kt", 3, startLine = 1, body = "x"), InlineCommentData("b.kt", 2, body = "y"))

            client().createCommentReview("o", "r", 1, "sha", comments)

            val body = sent.single()
            assertEquals("COMMENT", body.getValue("event").jsonPrimitive.content)
            assertEquals("sha", body.getValue("commit_id").jsonPrimitive.content)
            val posted = body.getValue("comments").jsonArray.map { it.jsonObject }
            assertEquals(listOf("a.kt", "b.kt"), posted.map { it.getValue("path").jsonPrimitive.content })
            assertEquals("1", posted[0].getValue("start_line").jsonPrimitive.content)
            assertFalse("start_line" in posted[1])
        }

    @Test
    fun `single-line comment has no start_line`() {
        post(startLine = null)

        assertFalse("start_line" in sent.single())
        assertFalse("start_side" in sent.single())
    }
}
