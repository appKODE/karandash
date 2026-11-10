package io.karandash.providers.lifecycle

import io.karandash.models.support.KarandashJson
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Stateful in-memory GitHub pull request (`o/r#7`) behind a Ktor [MockEngine]: issue comments, review threads over
 * GraphQL and raw files at the current head. Every write is recorded in [mutations]. Single-threaded test use only.
 */
class FakeGitHub {
    private class Thread(
        val id: String,
        val path: String,
        val line: Int,
        var resolved: Boolean = false,
        val comments: MutableList<Pair<Long, String>> = mutableListOf(),
    )

    private val issueComments = mutableListOf<Pair<Long, String>>()
    private val threads = mutableListOf<Thread>()
    private var files: Map<String, String> = emptyMap()
    private var nextId = 1L

    /** Every write request, as `METHOD path` (GraphQL mutations as `POST graphql <operation>`). */
    val mutations = mutableListOf<String>()

    /** Review comments posted, one entry per comment. */
    val reviewComments = mutableListOf<String>()

    /** How many reviews were submitted. */
    var reviews = 0

    fun setHead(content: Map<String, String>) {
        files = content
    }

    private val gitHubService = GitHubPlatformService(MockEngine { handle(it) })
    private var initialized = false

    suspend fun service(): GitHubPlatformService {
        if (!initialized) {
            check(gitHubService.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t")))))) { "rejected" }
            initialized = true
        }
        return gitHubService
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath
        val body = (request.body as? TextContent)?.text ?: (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
        val json = body?.let { KarandashJson.Default.parseToJsonElement(it).jsonObject }
        if (path == "/graphql") return graphql(checkNotNull(json))
        if (request.method != HttpMethod.Get) mutations += "${request.method.value} $path"
        val text = json
            ?.get("body")
            ?.jsonPrimitive
            ?.content
            .orEmpty()
        return when {
            path == "/user" -> ok("""{"login":"$BOT","id":1}""")
            path.endsWith("/permission") -> ok("""{"permission":"write"}""")
            path.startsWith("$REPO/contents/") -> rawFile(path.removePrefix("$REPO/contents/").decodeURLPart())
            path == "$REPO/issues/7/comments" && request.method == HttpMethod.Get -> ok(issueCommentsJson())
            path == "$REPO/issues/7/comments" -> ok(comment(nextId++.also { issueComments += it to text }, text))
            path.startsWith("$REPO/issues/comments/") -> editIssueComment(path.substringAfterLast('/').toLong(), text)
            path == "$REPO/pulls/7/reviews" -> review(checkNotNull(json))
            path.startsWith("$REPO/pulls/comments/") -> editReviewComment(path.substringAfterLast('/').toLong(), text)
            else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, JSON)
        }
    }

    private fun MockRequestHandleScope.review(json: JsonObject): HttpResponseData {
        reviews++
        json["comments"]?.jsonArray?.forEach {
            val comment = it.jsonObject
            val text = checkNotNull(comment["body"]).jsonPrimitive.content
            reviewComments += text
            val thread = Thread("T${nextId++}", comment.string("path"), comment.string("line").toInt())
            thread.comments += nextId++ to text
            threads += thread
        }
        return ok("{}")
    }

    private fun MockRequestHandleScope.editIssueComment(
        id: Long,
        text: String,
    ): HttpResponseData {
        issueComments.indices.filter { issueComments[it].first == id }.forEach { issueComments[it] = id to text }
        return ok(comment(id, text))
    }

    private fun MockRequestHandleScope.editReviewComment(
        id: Long,
        text: String,
    ): HttpResponseData {
        threads.forEach { t ->
            t.comments.indices
                .filter { t.comments[it].first == id }
                .forEach { t.comments[it] = id to text }
        }
        return ok("{}")
    }

    private fun MockRequestHandleScope.graphql(json: JsonObject): HttpResponseData {
        val query = json.string("query")
        val variables = checkNotNull(json["variables"]).jsonObject
        val operation = OPERATIONS.first { it in query }
        if (operation != LIST) mutations += "POST graphql $operation"
        val thread = variables["id"]?.jsonPrimitive?.content?.let { id -> threads.single { it.id == id } }
        when (operation) {
            RESOLVE -> checkNotNull(thread).resolved = true
            REPLY -> checkNotNull(thread).comments += nextId++ to variables.string("body")
        }
        return ok(if (operation == LIST) threadsJson() else """{"data":{}}""")
    }

    private fun threadsJson(): String =
        buildJsonObject {
            putJsonObject("data") {
                putJsonObject("repository") {
                    putJsonObject("pullRequest") {
                        putJsonObject("reviewThreads") {
                            putJsonObject("pageInfo") { put("hasNextPage", false) }
                            putJsonArray("nodes") { threads.forEach { addJsonObject { thread(it) } } }
                        }
                    }
                }
            }
        }.toString()

    private fun JsonObjectBuilder.thread(t: Thread) {
        put("id", t.id)
        put("isResolved", t.resolved)
        put("path", t.path)
        put("line", t.line)
        putJsonObject("comments") {
            putJsonArray("nodes") {
                t.comments.forEach { (id, text) ->
                    addJsonObject {
                        put("fullDatabaseId", id.toString())
                        put("body", text)
                        putJsonObject("author") { put("login", BOT) }
                    }
                }
            }
        }
    }

    private fun issueCommentsJson(): String =
        buildJsonArray { issueComments.forEach { (id, text) -> add(commentJson(id, text)) } }.toString()

    private fun comment(
        id: Long,
        text: String,
    ): String = commentJson(id, text).toString()

    private fun commentJson(
        id: Long,
        text: String,
    ) = buildJsonObject {
        put("id", id)
        put("body", text)
        putJsonObject("user") {
            put("login", BOT)
            put("id", 1)
        }
    }

    private fun MockRequestHandleScope.rawFile(path: String): HttpResponseData {
        val content = files[path] ?: return respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, JSON)
        return respond(content, HttpStatusCode.OK)
    }

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, JSON)

    private fun JsonObject.string(key: String): String = checkNotNull(this[key]) { "no $key" }.jsonPrimitive.content

    private companion object {
        const val BOT = "karandash-bot"
        const val REPO = "/repos/o/r"
        const val LIST = "reviewThreads"
        const val RESOLVE = "resolveReviewThread"
        const val REPLY = "addPullRequestReviewThreadReply"

        // The mutations name reviewThreads in their selection too, so they are matched first
        val OPERATIONS = listOf(RESOLVE, REPLY, LIST)
        val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
