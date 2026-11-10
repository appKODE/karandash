package io.karandash.providers.lifecycle

import io.karandash.models.support.KarandashJson
import io.karandash.platforms.gitlab.GitLabDiscussionDetail
import io.karandash.platforms.gitlab.GitLabMember
import io.karandash.platforms.gitlab.GitLabNote
import io.karandash.platforms.gitlab.GitLabNotePosition
import io.karandash.platforms.gitlab.GitLabPlatformService
import io.karandash.platforms.gitlab.GitLabUser
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
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stateful in-memory GitLab merge request (project 42, MR 7) behind a Ktor [MockEngine]: diff discussions with
 * replies, MR notes, raw files at the current head and project members. Every write the bot makes is recorded, so a
 * test can count posts, resolves and replies across several pushes. Single-threaded test use only.
 */
class FakeGitLab(
    private val members: Set<String> = setOf(MEMBER),
) {
    val discussions = mutableListOf<GitLabDiscussionDetail>()
    private val mrNotes = mutableListOf<GitLabNote>()
    private var files: Map<String, String> = emptyMap()
    private var nextId = 1L

    /** Bodies of every new diff discussion the bot opened. */
    val posts = mutableListOf<String>()

    /** Ids of every discussion the bot resolved. */
    val resolves = mutableListOf<String>()

    /** Bodies of every reply the bot added to a discussion. */
    val replies = mutableListOf<String>()

    /** Bodies of every discussion note the bot edited. */
    val noteEdits = mutableListOf<String>()

    /** Every write request (anything but GET), as `METHOD path`. */
    val mutations = mutableListOf<String>()

    /** How many `bulk_publish` calls published draft notes. */
    var bulkPublishes = 0

    /** Whether this GitLab has draft notes; without them the draft endpoints answer 404. */
    var draftNotes = true

    private val drafts = mutableListOf<JsonObject>()

    /** The summary note body, or null before the first one. */
    val summary: String? get() = mrNotes.lastOrNull()?.body

    /** How many of the next discussion listings fail (403), as a flaky API or a token without read access. */
    var failListings = 0

    /** How many of the next new diff discussions fail (403). */
    var failPosts = 0

    /** How many of the next thread resolves fail (403). */
    var failResolves = 0

    /** Head files the token may not read (403), as a path outside the token's scope. */
    var forbiddenFiles: Set<String> = emptySet()

    /** Makes [content] (path to text) the files at head; a path left out is missing (404). */
    fun setHead(content: Map<String, String>) {
        files = content
    }

    /** Adds a diff discussion opened by [author] on [path]:[line], as an earlier run or release left it. */
    fun addDiscussion(
        body: String,
        path: String,
        line: Int,
        author: String = BOT,
    ): String {
        val id = "d${nextId++}"
        discussions += GitLabDiscussionDetail(id = id, notes = listOf(note(body, author, path, line)))
        return id
    }

    /** Adds a reply by [author] to discussion [discussionId]. */
    fun reply(
        discussionId: String,
        body: String,
        author: String = MEMBER,
    ) = update(discussionId) { it.copy(notes = it.notes + note(body, author, null, null)) }

    /** Reopens discussion [discussionId], as a person clicking "Unresolve thread". */
    fun reopen(discussionId: String) =
        update(discussionId) { d -> d.copy(resolved = false, notes = d.notes.map { it.copy(resolved = false, resolvedBy = null) }) }

    private val gitLabService = GitLabPlatformService(MockEngine { handle(it) })
    private var initialized = false

    /** The [GitLabPlatformService] whose API calls hit this fake, initialized on first use. */
    suspend fun service(): GitLabPlatformService {
        if (!initialized) {
            val config = JsonObject(mapOf("url" to JsonPrimitive("https://gitlab.example.com"), "token" to JsonPrimitive("t")))
            check(gitLabService.initialize(TestPluginContext(config))) { "GitLab service rejected the fake's config" }
            initialized = true
        }
        return gitLabService
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath.removePrefix(PROJECT)
        val body = (request.body as? TextContent)?.text?.let { KarandashJson.Default.parseToJsonElement(it).jsonObject }
        val discussionId = DISCUSSION_REGEX.matchEntire(path)?.groupValues
        if (request.method != HttpMethod.Get) mutations += "${request.method.value} $path"
        if (failsNow(request.method, path, discussionId)) return forbidden()
        if (path.startsWith("$MR/draft_notes")) return draftNote(request, path, body)
        return when {
            request.method == HttpMethod.Get && path == "$MR/discussions" -> {
                json(discussionsJson())
            }

            request.method == HttpMethod.Get && path.endsWith("/award_emoji") -> {
                json("[]")
            }

            request.method == HttpMethod.Post && path == "$MR/discussions" -> {
                json(openDiscussion(checkNotNull(body)))
            }

            request.method == HttpMethod.Put && discussionId != null && discussionId[2].isEmpty() -> {
                resolve(discussionId[1])
                json("{}")
            }

            request.method == HttpMethod.Post && discussionId != null && discussionId[2] == "/notes" -> {
                val text = checkNotNull(body)["body"]?.jsonPrimitive?.content.orEmpty()
                replies += text
                reply(discussionId[1], text, BOT)
                json("{}")
            }

            request.method == HttpMethod.Put && discussionId != null -> {
                editNote(discussionId[1], discussionId[2].substringAfterLast('/').toLong(), checkNotNull(body))
                json("{}")
            }

            path.startsWith("/repository/files/") -> {
                rawFile(path)
            }

            request.method == HttpMethod.Get && request.url.encodedPath == "/api/v4/user" -> {
                json(KarandashJson.Default.encodeToString(GitLabUser.serializer(), user(BOT)))
            }

            request.method == HttpMethod.Get && path == "/members/all" -> {
                membersJson(request.url.parameters["query"])
            }

            else -> {
                mrNote(request, path, body)
            }
        }
    }

    private fun MockRequestHandleScope.mrNote(
        request: HttpRequestData,
        path: String,
        body: JsonObject?,
    ): HttpResponseData {
        val text = body
            ?.get("body")
            ?.jsonPrimitive
            ?.content
            .orEmpty()
        return when {
            request.method == HttpMethod.Get && path == "$MR/notes" -> {
                json(KarandashJson.Default.encodeToString(ListSerializer(GitLabNote.serializer()), mrNotes))
            }

            request.method == HttpMethod.Post && path == "$MR/notes" -> {
                val created = note(text, BOT, null, null)
                mrNotes += created
                json(KarandashJson.Default.encodeToString(GitLabNote.serializer(), created))
            }

            request.method == HttpMethod.Put && path.startsWith("$MR/notes/") -> {
                val id = path.substringAfterLast('/').toLong()
                mrNotes.indices.forEach { i -> if (mrNotes[i].id == id) mrNotes[i] = mrNotes[i].copy(body = text) }
                json(KarandashJson.Default.encodeToString(GitLabNote.serializer(), mrNotes.first { it.id == id }))
            }

            else -> {
                respond("""{"message":"404 Not Found"}""", HttpStatusCode.NotFound, JSON)
            }
        }
    }

    private fun MockRequestHandleScope.draftNote(
        request: HttpRequestData,
        path: String,
        body: JsonObject?,
    ): HttpResponseData =
        when {
            !draftNotes -> {
                respond("""{"message":"404 Not Found"}""", HttpStatusCode.NotFound, JSON)
            }

            request.method == HttpMethod.Post && path == "$MR/draft_notes" -> {
                drafts += checkNotNull(body)
                json("{}")
            }

            request.method == HttpMethod.Post && path == "$MR/draft_notes/bulk_publish" -> {
                bulkPublishes++
                drafts.forEach { openDiscussion(JsonObject(it + ("body" to checkNotNull(it["note"])))) }
                drafts.clear()
                respond("", HttpStatusCode.NoContent)
            }

            else -> {
                respond("""{"message":"404 Not Found"}""", HttpStatusCode.NotFound, JSON)
            }
        }

    private fun MockRequestHandleScope.rawFile(path: String): HttpResponseData {
        val file = path.removePrefix("/repository/files/").removeSuffix("/raw").decodeURLPart()
        if (file in forbiddenFiles) return forbidden()
        val content = files[file] ?: return respond("""{"message":"404 File Not Found"}""", HttpStatusCode.NotFound, JSON)
        return respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
    }

    private fun MockRequestHandleScope.membersJson(query: String?): HttpResponseData {
        val found = members.filter { query != null && it.contains(query) }
        val list = found.map { GitLabMember(id = it.length.toLong(), username = it, name = it, accessLevel = DEVELOPER) }
        return json(KarandashJson.Default.encodeToString(ListSerializer(GitLabMember.serializer()), list))
    }

    private fun discussionsJson() = KarandashJson.Default.encodeToString(ListSerializer(GitLabDiscussionDetail.serializer()), discussions)

    private fun openDiscussion(request: JsonObject): String {
        val text = request["body"]?.jsonPrimitive?.content.orEmpty()
        val position = checkNotNull(request["position"]).jsonObject
        posts += text
        val id = addDiscussion(
            text,
            checkNotNull(position["new_path"]).jsonPrimitive.content,
            checkNotNull(position["new_line"]).jsonPrimitive.content.toInt(),
        )
        return KarandashJson.Default.encodeToString(GitLabDiscussionDetail.serializer(), discussions.first { it.id == id })
    }

    private fun resolve(discussionId: String) {
        resolves += discussionId
        update(discussionId) { d -> d.copy(resolved = true, notes = d.notes.map { it.copy(resolved = true, resolvedBy = user(BOT)) }) }
    }

    private fun editNote(
        discussionId: String,
        noteId: Long,
        request: JsonObject,
    ) {
        val text = request["body"]?.jsonPrimitive?.content.orEmpty()
        noteEdits += text
        update(discussionId) { d -> d.copy(notes = d.notes.map { if (it.id == noteId) it.copy(body = text) else it }) }
    }

    private fun update(
        discussionId: String,
        change: (GitLabDiscussionDetail) -> GitLabDiscussionDetail,
    ) = discussions.indices.forEach { i -> if (discussions[i].id == discussionId) discussions[i] = change(discussions[i]) }

    private fun note(
        body: String,
        author: String,
        path: String?,
        line: Int?,
    ) = GitLabNote(
        id = nextId++,
        body = body,
        author = user(author),
        createdAt = "2026-10-21T10:00:00Z",
        resolvable = path != null,
        position = path?.let { GitLabNotePosition(newPath = it, oldPath = it, newLine = line) },
    )

    /** Whether this request is one of the failures a test asked for; spends that failure. */
    private fun failsNow(
        method: HttpMethod,
        path: String,
        discussionId: List<String>?,
    ): Boolean =
        when {
            method == HttpMethod.Get && path == "$MR/discussions" && failListings > 0 -> failListings-- > 0
            method == HttpMethod.Post && path in setOf("$MR/discussions", "$MR/draft_notes") && failPosts > 0 -> failPosts-- > 0
            method == HttpMethod.Put && discussionId?.get(2)?.isEmpty() == true && failResolves > 0 -> failResolves-- > 0
            else -> false
        }

    private fun MockRequestHandleScope.forbidden() = respond("""{"message":"403 Forbidden"}""", HttpStatusCode.Forbidden, JSON)

    private fun MockRequestHandleScope.json(body: String) = respond(body, HttpStatusCode.OK, JSON)

    companion object {
        /** A GitLab user named [name]. */
        fun user(name: String) = GitLabUser(id = name.length.toLong(), username = name, name = name)

        /** Username of the bot account the service posts as. */
        const val BOT = "karandash-bot"

        /** A project member. */
        const val MEMBER = "alice"

        private const val DEVELOPER = 30
        private const val PROJECT = "/api/v4/projects/42"
        private const val MR = "/merge_requests/7"
        private val DISCUSSION_REGEX = Regex("$MR/discussions/([^/]+)(/notes(?:/\\d+)?)?")
        private val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
