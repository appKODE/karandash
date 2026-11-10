package io.karandash.platforms.bitbucket

import io.karandash.api.support.rethrowCancellation
import io.karandash.http.HttpAuth
import io.karandash.http.ProviderResponseCache
import io.karandash.pagination.PaginationResult
import io.karandash.pagination.paginateByUrl
import io.karandash.providers.common.BaseProviderClient
import io.karandash.providers.common.UrlValidator
import io.karandash.providers.common.jsonListIds
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * HTTP client for Bitbucket API (provider operations: fetch PR, commits, diff stats, blame).
 *
 * Authenticates with `Bearer <token>`, or with HTTP Basic `username:<app password>` when [username] is set.
 * [baseUrl] is the API root (default Bitbucket Cloud), validated against SSRF by [BaseProviderClient].
 */
class BitbucketClient(
    private val token: String? = null,
    httpClient: HttpClient? = null,
    allowLocalhost: Boolean = false,
    logger: Logger = NoOpLogger,
    responseCache: ProviderResponseCache? = null,
    private val pageSize: Int = PAGE_SIZE,
    baseUrl: String = BitbucketConfig.DEFAULT_URL,
    private val username: String? = null,
    engine: HttpClientEngine? = null,
) : BaseProviderClient(
        baseUrl = baseUrl,
        httpClient = httpClient,
        allowLocalhost = allowLocalhost,
        logger = logger,
        responseCache = responseCache,
        engine = engine,
    ) {
    override val platformName: String = "Bitbucket"

    override fun credential(): HttpAuth? {
        val secret = token ?: return null
        return if (username != null) HttpAuth.Basic(username, secret) else HttpAuth.Bearer(secret)
    }

    /**
     * Every page of a Bitbucket list endpoint, following each page's `next` URL. Stops after [maxPages] pages
     * with a warning that later items are missing.
     */
    private suspend inline fun <reified T : BitbucketPagedResponse<R>, reified R> fetchPaginated(initialUrl: String): List<R> {
        val separator = if ('?' in initialUrl) '&' else '?'
        val onTruncated = warnTruncated("Bitbucket GET ${initialUrl.removePrefix(baseUrl)}")
        return paginateByUrl("$initialUrl${separator}pagelen=$pageSize", maxPages(), onTruncated) { url ->
            // Validate pagination URL matches expected base to prevent SSRF
            UrlValidator.validateUrlMatchesBase(url, baseUrl)
            val page: T = cachedGetAndDecode(url, "GET ${url.removePrefix(baseUrl)}")
            PaginationResult(page.values, page.next)
        }
    }

    /** Ids of the open PRs into [destination], most recently updated first, at most [limit]. */
    suspend fun listOpenPullRequestIds(
        workspace: String,
        repoSlug: String,
        destination: String,
        limit: Int,
    ): List<String> {
        val query = "destination.branch.name=\"$destination\"".encodeURLParameter()
        val url = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests?state=OPEN&q=$query&sort=-updated_on&pagelen=$limit"
        val body = cachedGetRaw(url, "GET /repositories/$workspace/$repoSlug/pullrequests?state=OPEN")
        return jsonListIds(body, "id", envelope = "values").take(limit)
    }

    suspend fun fetchPullRequest(
        workspace: String,
        repoSlug: String,
        prId: String,
    ): BitbucketPullRequest {
        val url = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId"
        val response = trackedRequest { get(url) { configureAuth() } }
        return requireSuccess(response, "GET /repositories/$workspace/$repoSlug/pullrequests/$prId").body()
    }

    suspend fun fetchPullRequest(
        workspace: String,
        repoSlug: String,
        prId: Int,
    ): BitbucketPullRequest = fetchPullRequest(workspace, repoSlug, prId.toString())

    suspend fun fetchCommits(
        workspace: String,
        repoSlug: String,
        prId: String,
    ): List<BitbucketCommit> =
        fetchPaginated<BitbucketCommitsResponse, BitbucketCommit>(
            "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/commits",
        )

    /** Build statuses reported on the pull request's commits. */
    suspend fun fetchStatuses(
        workspace: String,
        repoSlug: String,
        prId: String,
    ): List<BitbucketCommitStatus> =
        fetchPaginated<BitbucketCommitStatusesResponse, BitbucketCommitStatus>(
            "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/statuses",
        )

    suspend fun fetchDiffStats(
        workspace: String,
        repoSlug: String,
        prId: String,
    ): List<BitbucketDiffStat> =
        fetchPaginated<BitbucketDiffStatsResponse, BitbucketDiffStat>(
            "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/diffstat",
        )

    /** The pull request's unified diff (`git diff` text), or null when Bitbucket does not answer with one. */
    suspend fun fetchDiff(
        workspace: String,
        repoSlug: String,
        prId: String,
    ): String? {
        val url = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/diff"
        val response = trackedRequest { get(url) { configureAuth() } }
        return response.bodyAsText().takeIf { response.status.isSuccess() }
    }

    suspend fun fetchFileHistory(
        workspace: String,
        repoSlug: String,
        revision: String,
        path: String,
    ): List<BitbucketFileHistoryEntry> =
        fetchPaginated<BitbucketFileHistoryResponse, BitbucketFileHistoryEntry>(
            "$baseUrl/repositories/$workspace/$repoSlug/filehistory/$revision/$path",
        )

    /** Raw content of [path] at [ref] (`GET /repositories/{ws}/{repo}/src/{ref}/{path}`), or null when missing, binary or over the cap. */
    suspend fun fetchFileContent(
        workspace: String,
        repoSlug: String,
        ref: String,
        path: String,
    ): String? =
        getFileTextOrNull(
            "$baseUrl/repositories/$workspace/$repoSlug/src/${ref.encodeURLParameter()}/${path.encodeURLPath()}",
            "content of $path at $ref",
        )

    suspend fun fetchCommitDetail(
        workspace: String,
        repoSlug: String,
        sha: String,
    ): BitbucketCommit {
        val url = "$baseUrl/repositories/$workspace/$repoSlug/commit/$sha"
        return trackedRequest { get(url) { configureAuth() } }.body()
    }

    /**
     * List all comments on a pull request, paginating through all pages.
     */
    suspend fun listComments(
        workspace: String,
        repoSlug: String,
        prId: Int,
    ): List<BitbucketComment> = fetchPaginated<BitbucketCommentsResponse, BitbucketComment>(commentsUrl(workspace, repoSlug, prId))

    /**
     * Post a comment on a pull request.
     */
    suspend fun postComment(
        workspace: String,
        repoSlug: String,
        prId: Int,
        content: String,
    ): BitbucketComment {
        val url = commentsUrl(workspace, repoSlug, prId)
        val body = PostCommentRequest(
            content = CommentContentRequest(raw = content),
        )
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return requireSuccess(response, "POST /repositories/$workspace/$repoSlug/pullrequests/$prId/comments").body()
    }

    /** Replies [content] to comment [parentId]. */
    suspend fun replyToComment(
        workspace: String,
        repoSlug: String,
        prId: Int,
        parentId: Long,
        content: String,
    ) {
        val body = buildJsonObject {
            putJsonObject("content") { put("raw", content) }
            putJsonObject("parent") { put("id", parentId) }
        }
        val response = trackedRequest {
            post(commentsUrl(workspace, repoSlug, prId)) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        requireSuccess(response, "POST /repositories/$workspace/$repoSlug/pullrequests/$prId/comments (reply)")
    }

    /** Resolves the thread started by comment [commentId]. */
    suspend fun resolveComment(
        workspace: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
    ) {
        val url = "${commentsUrl(workspace, repoSlug, prId)}/$commentId/resolve"
        requireSuccess(trackedRequest { post(url) { configureAuth() } }, "POST comments/$commentId/resolve")
    }

    /**
     * Post an inline comment on a specific file and line of a pull request diff.
     */
    suspend fun createInlineComment(
        workspace: String,
        repoSlug: String,
        prId: Int,
        body: String,
        filePath: String,
        line: Int,
        startLine: Int? = null,
    ): BitbucketComment {
        val url = commentsUrl(workspace, repoSlug, prId)
        val requestBody = PostInlineCommentRequest(
            content = CommentContentRequest(raw = body),
            inline = InlineCommentPosition(path = filePath, to = line, startTo = startLine?.takeIf { it < line }),
        )
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        }
        return requireSuccess(response, "POST /repositories/$workspace/$repoSlug/pullrequests/$prId/comments").body()
    }

    /**
     * Update an existing comment on a pull request.
     */
    suspend fun updateComment(
        workspace: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
        content: String,
    ) {
        val url = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/comments/$commentId"
        val body = PostCommentRequest(
            content = CommentContentRequest(raw = content),
        )
        val response = trackedRequest {
            put(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        requireSuccess(response, "PUT /repositories/$workspace/$repoSlug/pullrequests/$prId/comments/$commentId")
    }

    /**
     * Delete a comment on a pull request.
     */
    suspend fun deleteComment(
        workspace: String,
        repoSlug: String,
        prId: Int,
        commentId: Long,
    ) {
        val url = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/comments/$commentId"
        requireSuccess(
            trackedRequest { delete(url) { configureAuth() } },
            "DELETE /repositories/$workspace/$repoSlug/pullrequests/$prId/comments/$commentId"
        )
    }

    /** The workspace's members (`GET /workspaces/{workspace}/members`), to resolve reviewer names to account UUIDs. */
    suspend fun listWorkspaceMembers(workspace: String): List<BitbucketUser> =
        fetchPaginated<BitbucketMembershipsResponse, BitbucketMembership>("$baseUrl/workspaces/$workspace/members").map { it.user }

    /** Replaces the pull request's reviewers with the accounts [uuids] (`PUT` with a `reviewers` list). */
    suspend fun setReviewers(
        workspace: String,
        repoSlug: String,
        prId: Int,
        uuids: List<String>,
    ) = putPullRequest(
        workspace,
        repoSlug,
        prId,
        buildJsonObject { putJsonArray("reviewers") { uuids.forEach { uuid -> addJsonObject { put("uuid", uuid) } } } }.toString(),
    )

    /**
     * Update a pull request (title, description, state, etc.).
     */
    suspend fun updatePullRequest(
        workspace: String,
        repoSlug: String,
        prId: Int,
        updates: Map<String, String?>,
    ) = putPullRequest(workspace, repoSlug, prId, buildUpdateBody(updates))

    private suspend fun putPullRequest(
        workspace: String,
        repoSlug: String,
        prId: Int,
        jsonBody: String,
    ) {
        val url = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId"
        val response = trackedRequest {
            put(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
        }
        requireSuccess(response, "PUT /repositories/$workspace/$repoSlug/pullrequests/$prId")
    }

    /** Merge a pull request; [body] carries `merge_strategy`, `close_source_branch` and `message`. */
    suspend fun mergePullRequest(
        workspace: String,
        repoSlug: String,
        prId: Int,
        body: JsonObject,
    ) {
        val url = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/merge"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        requireSuccess(response, "POST /repositories/$workspace/$repoSlug/pullrequests/$prId/merge")
    }

    /** Decline (close) a pull request. Bitbucket Cloud cannot reopen a declined pull request. */
    suspend fun declinePullRequest(
        workspace: String,
        repoSlug: String,
        prId: Int,
    ) {
        val url = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/decline"
        requireSuccess(
            trackedRequest { post(url) { configureAuth() } },
            "POST /repositories/$workspace/$repoSlug/pullrequests/$prId/decline"
        )
    }

    /**
     * Approve a pull request.
     */
    suspend fun approvePullRequest(
        workspace: String,
        repoSlug: String,
        prId: Int,
    ) {
        val url = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/approve"
        requireSuccess(
            trackedRequest { post(url) { configureAuth() } },
            "POST /repositories/$workspace/$repoSlug/pullrequests/$prId/approve"
        )
    }

    /**
     * Unapprove a pull request.
     */
    suspend fun unapprovePullRequest(
        workspace: String,
        repoSlug: String,
        prId: Int,
    ) {
        val url = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/approve"
        requireSuccess(
            trackedRequest { delete(url) { configureAuth() } },
            "DELETE /repositories/$workspace/$repoSlug/pullrequests/$prId/approve"
        )
    }

    /**
     * Fetch the UUID of the currently authenticated user.
     * Returns null if the API call fails.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun fetchCurrentUserUuid(): String? =
        try {
            val user: BitbucketUser = trackedRequest {
                get("$baseUrl/user") {
                    configureAuth()
                    accept(ContentType.Application.Json)
                }
            }.body()
            user.uuid
        } catch (e: Exception) {
            // boundary: platform API call; approval lookup is best-effort
            e.rethrowCancellation()
            logger.warn("Bitbucket fetchCurrentUserUuid failed (${e.message ?: e::class.simpleName})")
            null
        }

    /**
     * Check if the current authenticated user has approved the given PR.
     * Returns false if the check fails (graceful degradation).
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun isApprovedByCurrentUser(
        workspace: String,
        repoSlug: String,
        prId: Int,
    ): Boolean {
        val uuid = fetchCurrentUserUuid() ?: return false
        return try {
            val pr = fetchPullRequest(workspace, repoSlug, prId)
            pr.participants.any { it.approved && it.user.uuid == uuid }
        } catch (e: Exception) {
            // boundary: platform API call; approval lookup is best-effort
            e.rethrowCancellation()
            logger.warn("Bitbucket isApprovedByCurrentUser failed (${e.message ?: e::class.simpleName})")
            false
        }
    }

    /** The authenticated account (`GET /user`). */
    internal suspend fun fetchCurrentUser(): BitbucketUser = cachedGetAndDecode("$baseUrl/user", "GET /user")

    /** Pull requests in every state matching the BBQL [query], most recently updated first, at most [limit]. */
    internal suspend fun searchPullRequests(
        workspace: String,
        repoSlug: String,
        query: String,
        limit: Int,
    ): List<BitbucketPullRequestHit> {
        val q = query.takeIf { it.isNotBlank() }?.let { "&q=${it.encodeURLParameter()}" }.orEmpty()
        val path = "/repositories/$workspace/$repoSlug/pullrequests"
        val url = "$baseUrl$path?state=OPEN&state=MERGED&state=DECLINED&state=SUPERSEDED$q&sort=-updated_on&pagelen=${limit.coerceIn(
            1,
            PAGE_SIZE
        )}"
        return cachedGetAndDecode<BitbucketPullRequestHits>(url, "GET $path").values.take(limit)
    }

    private fun commentsUrl(
        workspace: String,
        repoSlug: String,
        prId: Int,
    ): String = "$baseUrl/repositories/$workspace/$repoSlug/pullrequests/$prId/comments"

    private companion object {
        /** Items per page; 50 is within every Bitbucket list endpoint's `pagelen` limit. */
        const val PAGE_SIZE = 50
    }
}

/**
 * Build a JSON body string from a map of string updates.
 * Used for updatePullRequest where we only need to send specific fields.
 */
internal fun buildUpdateBody(updates: Map<String, String?>): String {
    val entries = updates.entries.joinToString(",") { (k, v) ->
        if (v == null) "\"$k\":null" else "\"$k\":\"${v.replace("\"", "\\\"")}\""
    }
    return "{$entries}"
}
