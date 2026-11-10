package io.karandash.plugins.outputs

import io.karandash.logger.Logger
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Slack bot-token mode: `chat.postMessage` the report into [channel], and on a later run for the same MR ([target])
 * `chat.update` that message instead of posting a new one. The message carries `metadata` (event type
 * `karandash_report`, payload `target`) so the next run finds it in `conversations.history`. The token goes only in
 * the `Authorization` header and never into a log or an error message.
 *
 * @lifecycle per-run -- built for one delivery.
 */
internal class SlackBotPoster(
    private val client: HttpClient,
    private val token: String,
    private val channel: String,
    private val logger: Logger,
) {
    /** Posts [body] (Block Kit JSON), or updates the message an earlier run posted for [target] when there is one. */
    suspend fun deliver(
        body: String,
        target: String,
    ) {
        val payload = KarandashJson.Default.parseToJsonElement(body).jsonObject
        val metadata = target.takeIf { it.isNotEmpty() }?.let(::metadata)
        val ts = metadata?.let { findMessage(target) }
        val message = payload + ("channel" to JsonPrimitive(channel)) + listOfNotNull(metadata?.let { "metadata" to it })
        if (ts == null) {
            call("chat.postMessage", JsonObject(message))
        } else {
            call("chat.update", JsonObject(message + ("ts" to JsonPrimitive(ts))))
        }
    }

    /** The `ts` of the newest message in [channel] carrying [target]; null when none is found or the lookup fails. */
    private suspend fun findMessage(target: String): String? {
        val response = client.get("$API/conversations.history") {
            bearerAuth(token)
            parameter("channel", channel)
            parameter("limit", HISTORY_LIMIT)
            parameter("include_all_metadata", true)
        }
        val reply = parse(response.bodyAsText())
        if (!response.status.isSuccess() || reply?.get("ok") != JsonPrimitive(true)) {
            logger.warn("Slack: earlier message lookup failed (${reply.error()}), posting a new message")
            return null
        }
        val messages = reply["messages"] as? JsonArray ?: return null
        return messages.firstNotNullOfOrNull { element ->
            val message = element as? JsonObject
            val meta = message?.get("metadata") as? JsonObject
            val payload = meta?.get("event_payload") as? JsonObject
            val matches = meta?.get("event_type") == JsonPrimitive(EVENT_TYPE) && payload?.get("target") == JsonPrimitive(target)
            if (matches) (message?.get("ts") as? JsonPrimitive)?.content else null
        }
    }

    private suspend fun call(
        method: String,
        body: JsonObject,
    ) {
        val response = client.post("$API/$method") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val reply = parse(response.bodyAsText())
        check(response.status.isSuccess() && reply?.get("ok") == JsonPrimitive(true)) {
            "Slack $method failed: HTTP ${response.status.value} ${reply.error()}"
        }
    }

    private fun metadata(target: String): JsonElement =
        JsonObject(
            mapOf(
                "event_type" to JsonPrimitive(EVENT_TYPE),
                "event_payload" to JsonObject(mapOf("target" to JsonPrimitive(target))),
            ),
        )

    private fun parse(text: String): JsonObject? =
        try {
            KarandashJson.Default.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            logger.warn("Slack: reply is not JSON (${e.message?.take(MAX_ERROR)})")
            null
        }

    private fun JsonObject?.error(): String = (this?.get("error") as? JsonPrimitive)?.content ?: "no error code"

    companion object {
        /** Slack Web API base; a fixed host, so no SSRF check is needed. */
        const val API = "https://slack.com/api"

        /** Metadata event type that marks karandash's report messages. */
        const val EVENT_TYPE = "karandash_report"

        private const val HISTORY_LIMIT = 200
        private const val MAX_ERROR = 200
    }
}
