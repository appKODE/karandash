package io.karandash.platforms.gerrit

import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GerritCommitMessageTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(currentMessage: String): GerritClient {
        val engine = MockEngine { request ->
            requests += request
            val body = if (request.method == HttpMethod.Get) {
                ")]}'\n" +
                    KarandashJson.Default.encodeToString(GerritCommitMessageInfo.serializer(), GerritCommitMessageInfo("s", currentMessage))
            } else {
                ""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(KarandashJson.Default) } }
        return GerritClient("https://gerrit.example.com", token = "pw", httpClient = httpClient, username = "bot")
    }

    private fun sentMessage(): String {
        val put = requests.single { it.method == HttpMethod.Put }
        assertEquals("/a/changes/7/message", put.url.encodedPath)
        val text = (put.body as? TextContent)?.text ?: (put.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        return Json
            .parseToJsonElement(text)
            .jsonObject
            .getValue("message")
            .jsonPrimitive.content
    }

    @Test
    fun `updateDescription replaces the body and keeps the subject and Change-Id footer`() =
        runTest {
            client("Fix login\n\nOld body text.\n\nChange-Id: I123\nSigned-off-by: Dev <dev@example.com>\n")
                .updateDescription("7", "New body.")

            assertEquals("Fix login\n\nNew body.\n\nChange-Id: I123\nSigned-off-by: Dev <dev@example.com>\n", sentMessage())
            assertTrue(requests.none { it.url.encodedPath.endsWith("/topic") }, "updateDescription must not touch the topic")
        }

    @Test
    fun `updateSubject replaces only the first line`() =
        runTest {
            client("Fix login\n\nBody.\n\nChange-Id: I123\n").updateSubject("7", "Fix logout")

            assertEquals("Fix logout\n\nBody.\n\nChange-Id: I123\n", sentMessage())
        }
}
