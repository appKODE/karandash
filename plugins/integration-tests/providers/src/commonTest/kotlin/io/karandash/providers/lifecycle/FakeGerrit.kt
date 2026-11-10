package io.karandash.providers.lifecycle

import io.karandash.models.support.KarandashJson
import io.karandash.platforms.gerrit.GerritPlatformService
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Stateful in-memory Gerrit change 7 behind a Ktor [MockEngine]: change messages and published inline comments, both
 * written by `POST .../review`. Every write is recorded in [mutations]. Single-threaded test use only.
 */
class FakeGerrit {
    private class Comment(
        val id: String,
        val path: String,
        val line: Int,
        val message: String,
        val inReplyTo: String?,
        val unresolved: Boolean?,
    )

    private val messages = mutableListOf<String>()
    private val comments = mutableListOf<Comment>()
    private var nextId = 1

    /** Every write request, as `METHOD path`. */
    val mutations = mutableListOf<String>()

    /** Inline comments posted that open a thread (not replies). */
    val inlinePosts = mutableListOf<String>()

    private val gerritService = GerritPlatformService(MockEngine { handle(it) })
    private var initialized = false

    suspend fun service(): GerritPlatformService {
        if (!initialized) {
            val config = mapOf("url" to "https://gerrit.example.com", "username" to "bot", "token" to "t")
            check(gerritService.initialize(TestPluginContext(JsonObject(config.mapValues { JsonPrimitive(it.value) })))) { "rejected" }
            initialized = true
        }
        return gerritService
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        if (request.method != HttpMethod.Get) mutations += "${request.method.value} $path"
        return when {
            request.method == HttpMethod.Post && path.endsWith("/review") -> {
                review(KarandashJson.Default.parseToJsonElement((request.body as TextContent).text).jsonObject)
                ok("{}")
            }

            path == "/a/changes/7" -> {
                ok(changeJson())
            }

            path == "/a/changes/7/comments" -> {
                ok(commentsJson())
            }

            else -> {
                respond("Not found", HttpStatusCode.NotFound)
            }
        }
    }

    private fun review(input: JsonObject) {
        input["message"]?.jsonPrimitive?.contentOrNull?.let { messages += "Patch Set 1:\n\n$it" }
        val omitDuplicates = input["omit_duplicate_comments"]?.jsonPrimitive?.booleanOrNull == true
        input["comments"]?.jsonObject?.forEach { (path, list) ->
            list.jsonArray.map { it.jsonObject }.forEach {
                val line = it["line"]?.jsonPrimitive?.content?.toInt() ?: 0
                val message = checkNotNull(it["message"]).jsonPrimitive.content
                // Gerrit drops a comment identical to a published one when omit_duplicate_comments is set
                if (omitDuplicates && comments.any { c -> c.path == path && c.line == line && c.message == message }) return@forEach
                val comment = Comment(
                    id = "c${nextId++}",
                    path = path,
                    line = line,
                    message = message,
                    inReplyTo = it["in_reply_to"]?.jsonPrimitive?.contentOrNull,
                    unresolved = it["unresolved"]?.jsonPrimitive?.booleanOrNull,
                )
                comments += comment
                if (comment.inReplyTo == null) inlinePosts += comment.message
            }
        }
    }

    private fun changeJson(): String =
        buildJsonObject {
            put("id", "demo~main~I1")
            put("project", "demo")
            put("change_id", "I1")
            put("subject", "Add key")
            put("status", "NEW")
            put("branch", "main")
            putJsonObject("owner") { put("_account_id", OWNER) }
            putJsonArray("messages") {
                messages.forEachIndexed { i, text ->
                    addJsonObject {
                        put("id", "m$i")
                        put("message", text)
                    }
                }
            }
        }.let { XSSI + it }

    private fun commentsJson(): String =
        buildJsonObject {
            comments.groupBy { it.path }.forEach { (path, list) ->
                putJsonArray(path) {
                    list.forEach {
                        addJsonObject {
                            put("id", it.id)
                            put("message", it.message)
                            put("line", it.line)
                            put("patch_set", 1)
                            it.inReplyTo?.let { reply -> put("in_reply_to", reply) }
                            it.unresolved?.let { open -> put("unresolved", open) }
                            putJsonObject("author") { put("_account_id", BOT) }
                        }
                    }
                }
            }
        }.let { XSSI + it }

    private fun MockRequestHandleScope.ok(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private companion object {
        const val XSSI = ")]}'\n"
        const val OWNER = 1000
        const val BOT = 1
    }
}
