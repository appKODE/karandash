package io.karandash.platforms.bitbucket

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BitbucketFileContentTest {
    /** Answers [body] on [path] and 404 elsewhere; records each request as `path?query`. */
    private fun http(
        calls: MutableList<String>,
        path: String,
        body: String,
    ) = HttpClient(
        MockEngine { request ->
            calls += request.url.encodedPath + "?" + request.url.encodedQuery
            if (request.url.encodedPath == path) respond(body, HttpStatusCode.OK) else respond("", HttpStatusCode.NotFound)
        },
    )

    @Test
    fun `fetchFileContent reads the file at the ref`() =
        runTest {
            val calls = mutableListOf<String>()
            val client = BitbucketClient(token = "t", httpClient = http(calls, "/2.0/repositories/ws/repo/src/abc123/src/a.kt", "line1\n"))
            assertEquals("line1\n", client.fetchFileContent("ws", "repo", "abc123", "src/a.kt"))
            assertEquals("/2.0/repositories/ws/repo/src/abc123/src/a.kt?", calls.single())
        }

    @Test
    fun `fetchFileContent is null for a missing file`() =
        runTest {
            val client = BitbucketClient(token = "t", httpClient = http(mutableListOf(), "/x", ""))
            assertNull(client.fetchFileContent("ws", "repo", "abc123", "gone.kt"))
        }
}
