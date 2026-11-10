package io.karandash.platforms.gitlab

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
import kotlin.test.Test
import kotlin.test.assertEquals

class GitLabBlameRequestTest {
    private suspend fun blameRequest(
        filePath: String,
        ref: String,
    ): Pair<String, String> {
        var path = ""
        var query = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            query = request.url.encodedQuery
            respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        GitLabClient(token = "t", httpClient = http, responseCache = null).fetchBlame("42", filePath, ref)
        return path to query
    }

    @Test
    fun `blame encodes the slashes of the file path`() =
        runTest {
            val (path, _) = blameRequest("src/a/B.kt", "main")

            assertEquals("/api/v4/projects/42/repository/files/src%2Fa%2FB.kt/blame", path)
        }

    @Test
    fun `blame encodes plus and hash in the ref`() =
        runTest {
            val (path, query) = blameRequest("a.kt", "feat+x#1")

            assertEquals("/api/v4/projects/42/repository/files/a.kt/blame", path)
            assertEquals("ref=feat%2Bx%231", query)
        }

    @Test
    fun `blame keeps a plain ref unchanged`() =
        runTest {
            assertEquals("ref=main", blameRequest("a.kt", "main").second)
        }
}
