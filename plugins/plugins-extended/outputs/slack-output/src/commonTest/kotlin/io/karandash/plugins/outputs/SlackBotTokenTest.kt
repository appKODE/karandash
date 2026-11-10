package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.helpers.output.OnError
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Bot-token mode posts with chat.postMessage, then updates the same MR's earlier message with chat.update. */
class SlackBotTokenTest {
    private class Call(
        val path: String,
        val auth: String?,
        val body: String,
    )

    private val report = FormattedReport(formatId = "slack", body = """{"blocks":[],"text":"Failed"}""")
    private val config = SlackOutputConfig(token = "xoxb-secret", channel = "C123", notificationTarget = "team/app!7")

    private val earlier =
        """{"ok":true,"messages":[{"ts":"1.0","metadata":{"event_type":"other","event_payload":{"target":"team/app!7"}}},""" +
            """{"ts":"2.0","metadata":{"event_type":"karandash_report","event_payload":{"target":"team/app!7"}}}]}"""

    private suspend fun deliver(
        history: String,
        config: SlackOutputConfig = this.config,
        reply: String = """{"ok":true}""",
    ): List<Call> {
        val calls = mutableListOf<Call>()
        val module = SlackOutputModule(
            MockEngine { request ->
                calls +=
                    Call(request.url.encodedPath, request.headers[HttpHeaders.Authorization], request.body.toByteArray().decodeToString())
                val text = if (request.url.encodedPath.endsWith("conversations.history")) history else reply
                respond(text, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        module.initialize(TestPluginContext())
        try {
            module.outputTyped(report, config)
        } finally {
            module.shutdown()
        }
        return calls
    }

    @Test
    fun `the first run posts a message carrying the MR as metadata`() =
        runTest {
            val calls = deliver("""{"ok":true,"messages":[]}""")

            assertEquals(listOf("/api/conversations.history", "/api/chat.postMessage"), calls.map { it.path })
            assertTrue(calls.all { it.auth == "Bearer xoxb-secret" })
            val body = Json.parseToJsonElement(calls[1].body).jsonObject
            assertEquals(JsonPrimitive("C123"), body["channel"])
            val metadata = checkNotNull(body["metadata"]).jsonObject
            assertEquals(JsonPrimitive("karandash_report"), metadata["event_type"])
            assertEquals(JsonPrimitive("team/app!7"), (metadata["event_payload"] as JsonObject)["target"])
        }

    @Test
    fun `a later run for the same MR updates the earlier message`() =
        runTest {
            val calls = deliver(earlier)

            assertEquals("/api/chat.update", calls.last().path)
            assertEquals(JsonPrimitive("2.0"), Json.parseToJsonElement(calls.last().body).jsonObject["ts"])
        }

    @Test
    fun `a failed lookup posts a new message and a local run skips the lookup`() =
        runTest {
            assertEquals("/api/chat.postMessage", deliver("""{"ok":false,"error":"missing_scope"}""").last().path)
            assertEquals(listOf("/api/chat.postMessage"), deliver(earlier, config.copy(notificationTarget = "")).map { it.path })
        }

    @Test
    fun `an ok false reply fails under onError fail without leaking the token`() =
        runTest {
            val error = assertFailsWith<IllegalStateException> {
                deliver(earlier, config.copy(onError = OnError.FAIL), reply = """{"ok":false,"error":"invalid_auth"}""")
            }
            assertTrue("invalid_auth" in error.message.orEmpty(), error.message)
            assertFalse("xoxb-secret" in error.message.orEmpty())
        }

    @Test
    fun `bot-token mode needs a channel`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                deliver(earlier, config.copy(channel = null, onError = OnError.FAIL))
            }
        }
}
