package io.karandash.platforms.gitlab

import io.karandash.api.finding.FindingFeedback
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitLabFindingFeedbackTest {
    private val marker1 = """<!-- karandash-finding {"rule":"r1","hash":1,"fp":"sem1","fpv":"anchor1"} -->"""
    private val marker2 = """<!-- karandash-finding {"rule":"r2","hash":2,"fp":"sem2"} -->"""

    private fun note(
        id: Int,
        body: String,
    ) = """{"id":$id,"body":${JsonPrimitive(body)},"author":{"id":1,"username":"bot","name":"Bot"}}"""

    private fun award(
        name: String,
        user: String,
    ) = """{"id":9,"name":"$name","user":{"id":2,"username":"$user","name":"$user"}}"""

    private suspend fun service(engine: MockEngine): GitLabPlatformService {
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        val service = GitLabPlatformService()
        service.client.rebuild("test") { GitLabClient(token = "t", httpClient = http, responseCache = null) }
        return service
    }

    @Test
    fun `thumbsdown by a human on a karandash note counts each finding in it as a false positive`() =
        runTest {
            val notes = listOf(note(1, "two findings\n$marker1\n$marker2"), note(2, "human comment"))
            val discussions = notes.joinToString(",", "[", "]") { """{"id":"d","notes":[$it]}""" }
            val awardCalls = mutableListOf<String>()
            val engine = MockEngine { request ->
                val path = request.url.encodedPath
                val reply = when {
                    path.endsWith("/notes/1/award_emoji") -> {
                        awardCalls += path
                        listOf(award("thumbsdown", "alice"), award("thumbsdown", "bot"), award("thumbsup", "carol"))
                            .joinToString(",", "[", "]")
                    }

                    path.endsWith("/award_emoji") -> {
                        awardCalls += path
                        "[]"
                    }

                    else -> {
                        discussions
                    }
                }
                respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }

            val feedback = service(engine).fetchFindingFeedback("g/p", "7").getOrThrow()

            assertEquals(
                listOf(
                    FindingFeedback(rule = "r1", fingerprint = "anchor1", author = "alice"),
                    FindingFeedback(rule = "r2", fingerprint = "sem2", author = "alice"),
                ),
                feedback,
            )
            assertEquals(1, awardCalls.size, "reactions are read only for karandash notes")
        }

    @Test
    fun `an API failure comes back as a failed result`() =
        runTest {
            val engine = MockEngine { respond("boom", HttpStatusCode.Forbidden) }

            assertTrue(service(engine).fetchFindingFeedback("g/p", "7").isFailure)
        }
}
