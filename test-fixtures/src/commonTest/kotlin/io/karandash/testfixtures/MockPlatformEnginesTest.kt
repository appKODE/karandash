package io.karandash.testfixtures

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MockPlatformEnginesTest {
    @Test
    fun `first matching route answers and every request is recorded`() =
        runTest {
            val server = RecordingMockServer(
                listOf(
                    route(HttpMethod.Post, "/notes", """{"id":1}""", HttpStatusCode.Created),
                    pathEndsWith("/notes", """[]"""),
                ),
            )
            val client = server.httpClient()

            val listed = client.get("https://git.example.com/api/notes?page=2")
            val posted = client.post("https://git.example.com/api/notes")
            val missing = client.get("https://git.example.com/api/unknown")

            assertEquals("[]", listed.bodyAsText())
            assertEquals(HttpStatusCode.Created, posted.status)
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals(
                listOf("GET /api/notes", "POST /api/notes", "GET /api/unknown"),
                server.requests().map { "${it.method.value} ${it.url.encodedPath}" },
            )
            client.close()
        }
}
