package io.karandash.platforms.github

import io.karandash.api.ModuleRegistryImpl
import io.karandash.http.HttpClientSettings
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.models.support.KarandashJson
import io.karandash.pagination.PaginationProbe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubReviewThreadsTest {
    private fun threads(
        bodies: MutableList<String>,
        answer: String,
    ) = threads(bodies) { answer }

    private fun threads(
        bodies: MutableList<String>,
        answer: (Int) -> String,
    ) = GitHubReviewThreads(
        GitHubClient(
            token = "t",
            httpClient = HttpClient(
                MockEngine { request ->
                    bodies += request.url.encodedPath + " " + request.body.toByteArray().decodeToString()
                    respond(answer(bodies.size), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                },
            ) { install(ContentNegotiation) { json(KarandashJson.Default) } },
        ),
    )

    @Test
    fun `list maps review threads with notes and position`() =
        runTest {
            val bodies = mutableListOf<String>()
            val answer = """{"data":{"repository":{"pullRequest":{"reviewThreads":{
                "pageInfo":{"hasNextPage":false,"endCursor":null},
                "nodes":[{"id":"PRRT_1","isResolved":true,"path":"a.kt","line":3,"resolvedBy":{"login":"rev"},
                  "comments":{"nodes":[{"fullDatabaseId":"42","body":"hi","createdAt":"2026-01-01T00:00:00Z","author":{"login":"bot"}}]}}]
            }}}}}"""
            val result = threads(bodies, answer).list("o", "r", 7)
            val thread = result.single()
            assertEquals("PRRT_1", thread.id)
            assertTrue(thread.resolved)
            assertEquals(3, thread.position?.newLine)
            assertEquals("42", thread.notes.single().id)
            assertEquals("rev", thread.notes.single().resolvedBy)
            assertTrue(bodies.single().startsWith("/graphql "))
        }

    @Test
    fun `resolve and reply send the thread node id`() =
        runTest {
            val bodies = mutableListOf<String>()
            val subject = threads(bodies, """{"data":{}}""")
            subject.resolve("PRRT_1")
            subject.reply("PRRT_1", "done")
            assertTrue("resolveReviewThread" in bodies[0] && "PRRT_1" in bodies[0])
            assertTrue("addPullRequestReviewThreadReply" in bodies[1] && "done" in bodies[1])
        }

    @Test
    fun `a GraphQL error fails the call`() =
        runTest {
            assertFailsWith<IllegalStateException> {
                threads(mutableListOf(), """{"errors":[{"message":"nope"}]}""").resolve("PRRT_1")
            }
        }

    private fun page(
        n: Int,
        hasNext: Boolean,
    ) = """{"data":{"repository":{"pullRequest":{"reviewThreads":{
        "pageInfo":{"hasNextPage":$hasNext,"endCursor":"c$n"},
        "nodes":[{"id":"PRRT_$n","comments":{"nodes":[]}}]}}}}}"""

    @Test
    fun `list follows hasNextPage and sends the cursor as after`() =
        runTest {
            val bodies = mutableListOf<String>()
            val result = threads(bodies) { n -> page(n, hasNext = n < 3) }.list("o", "r", 7)
            assertEquals(listOf("PRRT_1", "PRRT_2", "PRRT_3"), result.map { it.id })
            assertTrue("\"after\":null" in bodies[0], bodies[0])
            assertTrue("\"after\":\"c1\"" in bodies[1], bodies[1])
            assertTrue("\"after\":\"c2\"" in bodies[2], bodies[2])
        }

    @Test
    fun `list stops at maxPages and marks the pagination probe truncated`() =
        runTest {
            val bodies = mutableListOf<String>()
            val session = ModuleRegistryImpl().forRun()
            session.provide(ProviderHttpClientFactory.HTTP_SETTINGS, HttpClientSettings(maxPages = 2))
            val (result, truncated) = withContext(session.services) {
                PaginationProbe.probe { threads(bodies) { n -> page(n, hasNext = true) }.list("o", "r", 7) }
            }
            assertEquals(2, bodies.size)
            assertEquals(2, result.size)
            assertTrue(truncated)
        }

    @Test
    fun `a thread with more than 100 comments still exposes the late bot note`() =
        runTest {
            val first = (1..100).joinToString(",") { """{"fullDatabaseId":"$it","body":"human $it"}""" }
            val last = (51..150).joinToString(",") { """{"fullDatabaseId":"$it","body":"${if (it == 150) "bot note" else "human $it"}"}""" }
            val answer = """{"data":{"repository":{"pullRequest":{"reviewThreads":{
                "pageInfo":{"hasNextPage":false,"endCursor":null},
                "nodes":[{"id":"PRRT_1","comments":{"nodes":[$first]},"latest":{"nodes":[$last]}}]}}}}}"""
            val bodies = mutableListOf<String>()
            val notes = threads(bodies, answer).list("o", "r", 7).single().notes
            assertEquals(150, notes.size)
            assertEquals("bot note", notes.last().body)
            assertTrue("latest: comments(last: 100)" in bodies.single(), bodies.single())
        }
}
