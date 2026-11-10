package io.karandash.platforms.gitea

import io.karandash.api.support.rethrowCancellation
import io.karandash.cache.CachedValue
import io.karandash.http.HttpAuth
import io.karandash.pagination.PaginationResult
import io.karandash.pagination.paginateByPageNumber
import io.karandash.pagination.parseLinkHeaderNext
import io.karandash.providers.common.BaseProviderClient
import io.karandash.providers.common.PathSegmentEncoder
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HTTP client for Gitea API
 *
 * Supports credential resolution from config or environment variables:
 * - Token: config.token > GITEA_TOKEN
 * - URL: config.url > GITEA_URL (required, no default)
 */
class GiteaClient(
    baseUrl: String,
    private val token: String? = null,
    httpClient: HttpClient? = null,
    allowLocalhost: Boolean = false,
    logger: Logger = NoOpLogger,
    private val pageSize: Int = MAX_PER_PAGE,
) : BaseProviderClient(
        baseUrl = baseUrl,
        httpClient = httpClient,
        allowLocalhost = allowLocalhost,
        logger = logger,
    ) {
    override val platformName: String = "Gitea"

    private val cachedUsername = MutableStateFlow<CachedValue<String?>>(CachedValue.Unresolved)
    private val cachedVersion = MutableStateFlow<CachedValue<String?>>(CachedValue.Unresolved)

    /**
     * Check if authentication token is configured
     */
    fun hasToken(): Boolean = token != null

    /** Gitea rejects every write without a token; fail before the request with the fix named. */
    private fun requireToken(action: String) = check(hasToken()) { "Gitea token required to $action (config 'token' or GITEA_TOKEN)" }

    override fun credential(): HttpAuth? = token?.let { HttpAuth.CustomHeader(HttpHeaders.Authorization, "token $it") }

    /**
     * Post a comment on a pull request
     * @param owner Repository owner
     * @param repo Repository name
     * @param prId Pull request number
     * @param body Comment body
     * @return GiteaComment object with the created comment
     */
    suspend fun postComment(
        owner: String,
        repo: String,
        prId: Int,
        body: String,
    ): GiteaComment {
        requireToken("post a comment")
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/issues/$prId/comments"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("body" to body))
            }
        }
        requireSuccess(response, "POST /repos/$owner/$repo/issues/$prId/comments")
        return response.body()
    }

    /**
     * All comments on a pull request. Gitea's `GET /repos/{owner}/{repo}/issues/{index}/comments` takes no
     * `page`/`limit` and returns every comment at once, so this is one request: paging it re-read the same full
     * list on every "page".
     */
    suspend fun listComments(
        owner: String,
        repo: String,
        prId: Int,
    ): List<GiteaComment> {
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/issues/$prId/comments"
        val response = trackedRequest { get(url) { configureAuth() } }
        requireSuccess(response, "GET ${url.removePrefix(baseUrl)}")
        return response.body()
    }

    /**
     * Update an existing comment on a pull request
     * @param owner Repository owner
     * @param repo Repository name
     * @param commentId The comment ID to update
     * @param body The new comment body
     */
    suspend fun updateComment(
        owner: String,
        repo: String,
        commentId: Long,
        body: String,
    ) {
        requireToken("update a comment")
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/issues/comments/$commentId"
        val response = trackedRequest {
            patch(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("body" to body))
            }
        }
        requireSuccess(response, "PATCH /repos/$owner/$repo/issues/comments/$commentId")
    }

    /**
     * Add labels to a pull request
     * @param owner Repository owner
     * @param repo Repository name
     * @param prId Pull request number
     * @param labelIds List of label IDs to add
     */
    suspend fun addLabels(
        owner: String,
        repo: String,
        prId: Int,
        labelIds: List<Long>,
    ) {
        requireToken("add labels")
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/issues/$prId/labels"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("labels" to labelIds))
            }
        }
        requireSuccess(response, "POST /repos/$owner/$repo/issues/$prId/labels")
    }

    /**
     * Remove a label from a pull request
     * @param owner Repository owner
     * @param repo Repository name
     * @param prId Pull request number
     * @param labelId Label ID to remove
     */
    suspend fun removeLabel(
        owner: String,
        repo: String,
        prId: Int,
        labelId: Long,
    ) {
        requireToken("remove a label")
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/issues/$prId/labels/$labelId"
        val response = trackedRequest { delete(url) { configureAuth() } }
        requireSuccess(response, "DELETE /repos/$owner/$repo/issues/$prId/labels/$labelId")
    }

    /**
     * List all labels for a repository, fetching all pages.
     * @param owner Repository owner
     * @param repo Repository name
     * @return List of GiteaLabel objects
     */
    suspend fun listLabels(
        owner: String,
        repo: String,
    ): List<GiteaLabel> {
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/labels"
        return paginateGet(url)
    }

    /**
     * Edit/update a pull request (used for label changes, title updates, etc.)
     * @param owner Repository owner
     * @param repo Repository name
     * @param prId Pull request number
     * @param updates JSON object of fields to update
     */
    suspend fun editPullRequest(
        owner: String,
        repo: String,
        prId: Int,
        updates: JsonObject,
    ) {
        requireToken("edit the pull request")
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/pulls/$prId"
        val response = trackedRequest {
            patch(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(updates)
            }
        }
        requireSuccess(response, "PATCH /repos/$owner/$repo/pulls/$prId")
    }

    /** Requests ([add]) or withdraws review from [logins] (`POST`/`DELETE /pulls/{index}/requested_reviewers`). */
    suspend fun requestReviewers(
        owner: String,
        repo: String,
        prId: Int,
        logins: List<String>,
        add: Boolean,
    ) {
        requireToken("change the reviewers")
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/pulls/$prId/requested_reviewers"
        val body = buildJsonObject { put("reviewers", JsonArray(logins.map(::JsonPrimitive))) }
        val response = trackedRequest {
            request(url) {
                method = if (add) HttpMethod.Post else HttpMethod.Delete
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        requireSuccess(response, "${if (add) "POST" else "DELETE"} /repos/$owner/$repo/pulls/$prId/requested_reviewers")
    }

    /** Merge a pull request; [body] carries `Do`, `MergeMessageField` and `delete_branch_after_merge`. */
    suspend fun mergePullRequest(
        owner: String,
        repo: String,
        prId: Int,
        body: JsonObject,
    ) {
        requireToken("merge the pull request")
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/pulls/$prId/merge"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        requireSuccess(response, "POST /repos/$owner/$repo/pulls/$prId/merge")
    }

    /**
     * Create a pull request review with an inline comment on a specific file and line.
     * @param owner Repository owner
     * @param repo Repository name
     * @param prId Pull request number
     * @param body Comment body
     * @param commitId The commit SHA to attach the comment to
     * @param path File path relative to repository root
     * @param newPosition Line number in the new version of the file
     * @return GiteaReviewResponse with the created review ID
     */
    suspend fun createPullReviewComment(
        owner: String,
        repo: String,
        prId: Int,
        body: String,
        commitId: String,
        path: String,
        newPosition: Int,
    ): GiteaReviewResponse = createPullReview(owner, repo, prId, commitId, listOf(GiteaReviewCommentRequest(path, newPosition, body)))

    /**
     * Create one review carrying all of [comments] as inline comments, so the author is notified once.
     *
     * @return GiteaReviewResponse with the created review ID
     */
    suspend fun createPullReview(
        owner: String,
        repo: String,
        prId: Int,
        commitId: String,
        comments: List<GiteaReviewCommentRequest>,
    ): GiteaReviewResponse {
        requireToken("post a review comment")
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/pulls/$prId/reviews"
        val request = GiteaCreateReviewRequest(body = "", event = "COMMENT", comments = comments, commitId = commitId)
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
        requireSuccess(response, "POST /repos/$owner/$repo/pulls/$prId/reviews")
        return response.body()
    }

    /**
     * Every inline review comment on pull request [prId]. Gitea has no per-PR comment list, so this reads each
     * review's comments (`GET pulls/{index}/reviews/{id}/comments`, not paged).
     */
    suspend fun listPullReviewComments(
        owner: String,
        repo: String,
        prId: Int,
    ): List<GiteaPullReviewComment> =
        listReviews(owner, repo, prId).flatMap { review ->
            val path = "/repos/$owner/$repo/pulls/$prId/reviews/${review.id}/comments"
            val response = trackedRequest { get("$baseUrl/api/v1$path") { configureAuth() } }
            requireSuccess(response, "GET $path")
            response.body<List<GiteaPullReviewComment>>()
        }

    /** Resolve the conversation of review comment [commentId] (`POST pulls/comments/{id}/resolve`, Gitea 1.26+). */
    suspend fun resolvePullReviewComment(
        owner: String,
        repo: String,
        commentId: Long,
    ) {
        requireToken("resolve a review comment")
        validateSegments(owner, repo)
        val path = "/repos/$owner/$repo/pulls/comments/$commentId/resolve"
        requireSuccess(trackedRequest { post("$baseUrl/api/v1$path") { configureAuth() } }, "POST $path")
    }

    /** The server version (`GET /version`), read once; null with a warning when the lookup fails. */
    suspend fun serverVersion(): String? {
        val current = cachedVersion.value
        if (current is CachedValue.Resolved) return current.value
        val resolved = try {
            trackedRequest { get("$baseUrl/api/v1/version") { configureAuth() } }.body<GiteaServerVersion>().version
        } catch (ignored: Exception) {
            // boundary: version probe; an unknown version falls back to the pre-1.26 behaviour
            ignored.rethrowCancellation()
            logger.warn("Gitea version lookup failed (${ignored.message ?: ignored::class.simpleName})")
            null
        }
        cachedVersion.value = CachedValue.Resolved(resolved)
        return resolved
    }

    /**
     * Delete a pull request comment by ID
     * @param owner Repository owner
     * @param repo Repository name
     * @param commentId The comment ID to delete
     */
    suspend fun deleteComment(
        owner: String,
        repo: String,
        commentId: Long,
    ) {
        requireToken("delete a comment")
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/issues/comments/$commentId"
        val response = trackedRequest { delete(url) { configureAuth() } }
        requireSuccess(response, "DELETE /repos/$owner/$repo/issues/comments/$commentId")
    }

    /**
     * Whether [username] can write to [owner]/[repo]: `permission` `write`, `admin` or `owner` on
     * `GET /repos/{owner}/{repo}/collaborators/{username}/permission`, team and organization grants included. A 404
     * (no such user or no access) is no.
     */
    suspend fun hasWriteAccess(
        owner: String,
        repo: String,
        username: String,
    ): Boolean {
        validateSegments(owner, repo)
        val response = trackedRequest {
            get("$baseUrl/api/v1/repos/$owner/$repo/collaborators/${PathSegmentEncoder.encode(username)}/permission") {
                configureAuth()
            }
        }
        if (response.status.value == HTTP_NOT_FOUND) return false
        requireSuccess(response, "GET /repos/$owner/$repo/collaborators/{username}/permission")
        return response.body<RepoPermission>().permission in WRITE_PERMISSIONS
    }

    /**
     * Fetch the login of the currently authenticated user.
     * Result is cached after the first call. Returns null if the API call fails.
     */
    suspend fun fetchCurrentUsername(): String? {
        val current = cachedUsername.value
        if (current is CachedValue.Resolved) return current.value
        val resolved = try {
            val user: GiteaUser = trackedRequest {
                get("$baseUrl/api/v1/user") { configureAuth() }
            }.body()
            user.login
        } catch (ignored: Exception) {
            // boundary: platform API call; approval lookup is best-effort
            ignored.rethrowCancellation()
            logger.warn("Gitea fetchCurrentUsername failed (${ignored.message ?: ignored::class.simpleName})")
            null
        }
        cachedUsername.value = CachedValue.Resolved(resolved)
        return resolved
    }

    /**
     * Check if the current authenticated user has an active approval on a PR.
     * Returns false if the check fails (graceful degradation).
     */
    suspend fun isApprovedByCurrentUser(
        owner: String,
        repo: String,
        prIndex: Int,
    ): Boolean {
        val username = fetchCurrentUsername() ?: return false
        return try {
            val reviews = listReviews(owner, repo, prIndex)
            reviews.any { it.state == REVIEW_STATE_APPROVED && it.user?.login == username }
        } catch (ignored: Exception) {
            // boundary: platform API call; approval lookup is best-effort
            ignored.rethrowCancellation()
            logger.warn("Gitea isApprovedByCurrentUser failed (${ignored.message ?: ignored::class.simpleName})")
            false
        }
    }

    /**
     * Create an approval review on a pull request.
     * @param owner Repository owner
     * @param repo Repository name
     * @param prIndex Pull request number
     * @return GiteaReviewResponse with the created review
     */
    suspend fun createApprovalReview(
        owner: String,
        repo: String,
        prIndex: Int,
    ): GiteaReviewResponse {
        requireToken("approve the pull request")
        validateSegments(owner, repo)
        val url = "$baseUrl/api/v1/repos/$owner/$repo/pulls/$prIndex/reviews"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("event" to REVIEW_STATE_APPROVED, "body" to "Auto-approved by Karandash"))
            }
        }
        requireSuccess(response, "POST /repos/$owner/$repo/pulls/$prIndex/reviews")
        return response.body()
    }

    /**
     * Dismiss the latest approval review on a pull request.
     * Fetches all reviews, finds the last APPROVED one, then dismisses it.
     * @param owner Repository owner
     * @param repo Repository name
     * @param prIndex Pull request number
     */
    suspend fun dismissApprovalReview(
        owner: String,
        repo: String,
        prIndex: Int,
    ) {
        requireToken("dismiss the approval")
        validateSegments(owner, repo)
        val reviews = listReviews(owner, repo, prIndex)
        val currentUser = fetchCurrentUsername()
        val approvalReview = if (currentUser != null) {
            reviews.lastOrNull { it.state == REVIEW_STATE_APPROVED && it.user?.login == currentUser }
        } else {
            reviews.lastOrNull { it.state == REVIEW_STATE_APPROVED }
        } ?: return
        val reviewId = approvalReview.id
        val url = "$baseUrl/api/v1/repos/$owner/$repo/pulls/$prIndex/reviews/$reviewId/dismissals"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("message" to "Auto-unapproved by Karandash"))
            }
        }
        requireSuccess(response, "POST /repos/$owner/$repo/pulls/$prIndex/reviews/$reviewId/dismissals")
    }

    suspend fun listReviews(
        owner: String,
        repo: String,
        prIndex: Int,
    ): List<GiteaReviewResponse> {
        val url = "$baseUrl/api/v1/repos/$owner/$repo/pulls/$prIndex/reviews"
        return paginateGet(url)
    }

    /**
     * All pages of [url]. Asks for the next page while the current one is full or Gitea's `Link` header names a
     * next one (Gitea may serve fewer items than `limit` asks for). Warns when the page cap cuts the list.
     */
    private suspend inline fun <reified T> paginateGet(url: String): List<T> {
        val label = "GET ${url.removePrefix(baseUrl)}"
        return paginateByPageNumber(maxPages(MAX_PAGES), warnTruncated("Gitea $label")) { page ->
            val response = trackedRequest { get("$url?limit=$pageSize&page=$page") { configureAuth() } }
            requireSuccess(response, label)
            val pageItems: List<T> = response.body()
            val more = pageItems.isNotEmpty() &&
                (pageItems.size >= pageSize || parseLinkHeaderNext(response.headers[HttpHeaders.Link]) != null)
            PaginationResult(pageItems, if (more) "${page + 1}" else null)
        }
    }

    private fun validateSegments(
        owner: String,
        repo: String,
    ) {
        PathSegmentEncoder.encode(owner)
        PathSegmentEncoder.encode(repo)
    }

    private companion object {
        private const val REVIEW_STATE_APPROVED = "APPROVED"
        private const val MAX_PER_PAGE = 50
        private const val MAX_PAGES = 20
    }
}
