@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.github

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.support.rethrowCancellation
import io.karandash.cache.CachedValue
import io.karandash.http.HttpAuth
import io.karandash.http.ProviderResponseCache
import io.karandash.providers.common.BaseProviderClient
import io.karandash.providers.common.PathSegmentEncoder
import io.karandash.providers.common.jsonListIds
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * HTTP client for GitHub API. Authenticates with [token], or as the GitHub App [app] with an installation token
 * exchanged on first use and refreshed before it expires or after a 401.
 */
class GitHubClient(
    baseUrl: String = "https://api.github.com",
    private val token: String? = null,
    httpClient: HttpClient? = null,
    allowLocalhost: Boolean = false,
    logger: Logger = NoOpLogger,
    responseCache: ProviderResponseCache? = null,
    private val pageSize: Int = MAX_PER_PAGE,
    app: GitHubAppCredentials? = null,
    clock: Clock = Clock.System,
    engine: HttpClientEngine? = null,
) : GitHubClientBase(
        baseUrl = baseUrl,
        httpClient = httpClient,
        allowLocalhost = allowLocalhost,
        logger = logger,
        responseCache = responseCache,
        engine = engine,
    ) {
    private val appTokens = app?.let { GitHubAppTokenCache(it, clock, ::exchangeInstallationToken) }

    override suspend fun prepareAuth() {
        appTokens?.ensureFresh()
    }

    override suspend fun onUnauthorized(): Boolean = appTokens?.invalidate() ?: false

    private suspend fun exchangeInstallationToken(
        installationId: String,
        jwt: String,
    ): GitHubInstallationToken {
        val url = "$baseUrl/app/installations/$installationId/access_tokens"
        val response = untrackedRequest {
            post(url) {
                header(HttpHeaders.Authorization, "Bearer $jwt")
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
        }
        return requireSuccess(response, "POST /app/installations/$installationId/access_tokens").body()
    }

    /** True when this client acts as a GitHub App installation rather than with a token. */
    val isAppAuth: Boolean get() = appTokens != null

    override fun credential(): HttpAuth? = (appTokens?.current() ?: token)?.let { HttpAuth.Bearer(it) }

    override fun HttpRequestBuilder.configureHeaders() {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    /**
     * Fetch a pull request from GitHub
     * @param owner Repository owner
     * @param repo Repository name
     * @param prNumber Pull request number
     * @return GitHubPullRequest data
     */
    suspend fun fetchPullRequest(
        owner: String,
        repo: String,
        prNumber: Int
    ): GitHubPullRequest {
        validateSegments(owner, repo)
        val url = pullUrl(owner, repo, prNumber)
        return cachedGetAndDecode(url, "GET /repos/$owner/$repo/pulls/$prNumber")
    }

    /** Numbers of the open PRs into [base], most recently updated first, at most [limit]. */
    suspend fun listOpenPullRequestNumbers(
        owner: String,
        repo: String,
        base: String,
        limit: Int,
    ): List<String> {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/pulls?state=open&base=${base.encodeURLParameter()}" +
            "&sort=updated&direction=desc&per_page=$limit"
        return jsonListIds(cachedGetRaw(url, "GET /repos/$owner/$repo/pulls?state=open"), "number").take(limit)
    }

    /** PRs in any state matching [text] and/or by [author], most recently updated first, at most [limit]. */
    suspend fun searchPullRequests(
        owner: String,
        repo: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit> {
        validateSegments(owner, repo)
        val url = "$baseUrl/search/issues?q=${gitHubSearchQuery(owner, repo, text, author)}&sort=updated&order=desc&per_page=$limit"
        return parseGitHubSearchHits(cachedGetRaw(url, "GET /search/issues")).take(limit)
    }

    /**
     * Fetch commits for a pull request
     * @param owner Repository owner
     * @param repo Repository name
     * @param prNumber Pull request number
     * @return List of commits
     */
    suspend fun fetchCommits(
        owner: String,
        repo: String,
        prNumber: Int
    ): List<GitHubCommit> {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/pulls/$prNumber/commits?per_page=$pageSize"
        return getAllPages(url, "GET /repos/$owner/$repo/pulls/$prNumber/commits")
    }

    /**
     * Fetch files changed in a pull request
     * @param owner Repository owner
     * @param repo Repository name
     * @param prNumber Pull request number
     * @return List of changed files
     */
    suspend fun fetchFiles(
        owner: String,
        repo: String,
        prNumber: Int
    ): List<GitHubFile> {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/pulls/$prNumber/files?per_page=$pageSize"
        return getAllPages(url, "GET /repos/$owner/$repo/pulls/$prNumber/files")
    }

    /**
     * Fetch reviews for a pull request
     * @param owner Repository owner
     * @param repo Repository name
     * @param prNumber Pull request number
     * @return List of reviews
     */
    override suspend fun fetchReviews(
        owner: String,
        repo: String,
        prNumber: Int
    ): List<GitHubReview> {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/pulls/$prNumber/reviews?per_page=$pageSize"
        return getAllPages(url, "GET /repos/$owner/$repo/pulls/$prNumber/reviews")
    }

    /**
     * Post a comment on a GitHub issue/PR.
     * Note: PR comments use the Issues API in GitHub.
     */
    suspend fun postIssueComment(
        owner: String,
        repo: String,
        issueNumber: Int,
        body: String
    ): GitHubComment {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/issues/$issueNumber/comments"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("body" to body))
            }
        }
        return requireSuccess(response, "POST /repos/$owner/$repo/issues/$issueNumber/comments").body()
    }

    /**
     * Update an existing issue/PR comment.
     */
    suspend fun updateIssueComment(
        owner: String,
        repo: String,
        commentId: Long,
        body: String
    ) = patchBody(owner, repo, "issues/comments/$commentId", body)

    private suspend fun patchBody(
        owner: String,
        repo: String,
        path: String,
        body: String,
    ) {
        validateSegments(owner, repo)
        val response = trackedRequest {
            patch("$baseUrl/repos/$owner/$repo/$path") {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("body" to body))
            }
        }
        requireSuccess(response, "PATCH /repos/$owner/$repo/$path")
    }

    /**
     * List all comments on an issue/PR, following pagination automatically.
     */
    suspend fun listIssueComments(
        owner: String,
        repo: String,
        issueNumber: Int
    ): List<GitHubComment> {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/issues/$issueNumber/comments?per_page=$pageSize"
        return getAllPages(url, "GET /repos/$owner/$repo/issues/$issueNumber/comments")
    }

    /** Reactions on the pull request itself (`GET /issues/:n/reactions`), all pages. */
    suspend fun listIssueReactions(
        owner: String,
        repo: String,
        issueNumber: Int
    ): List<GitHubReaction> {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/issues/$issueNumber/reactions?per_page=$pageSize"
        return getAllPages(url, "GET /repos/$owner/$repo/issues/$issueNumber/reactions")
    }

    /** Merge a pull request (`PUT /pulls/:n/merge`); [body] carries `merge_method` and `commit_message`. */
    suspend fun mergePullRequest(
        owner: String,
        repo: String,
        prNumber: Int,
        body: JsonObject,
    ) {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/pulls/$prNumber/merge"
        val response = trackedRequest {
            put(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        requireSuccess(response, "PUT /repos/$owner/$repo/pulls/$prNumber/merge")
    }

    /** Core REST rate limit (`GET /rate_limit`, which does not count against it). */
    suspend fun fetchRateLimit(): GitHubRate {
        val response = trackedRequest {
            get("$baseUrl/rate_limit") {
                configureAuth()
                accept(ContentType.Application.Json)
            }
        }
        requireSuccess(response, "GET /rate_limit")
        return response.body<GitHubRateLimitResponse>().rate
    }

    /** Set a pull request's `state` (`open` or `closed`). */
    suspend fun setPullRequestState(
        owner: String,
        repo: String,
        prNumber: Int,
        state: String,
    ) {
        validateSegments(owner, repo)
        val url = pullUrl(owner, repo, prNumber)
        val response = trackedRequest {
            patch(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("state" to state))
            }
        }
        requireSuccess(response, "PATCH /repos/$owner/$repo/pulls/$prNumber")
    }

    /**
     * Update a pull request (used for label changes, draft toggling, etc.)
     * @param owner Repository owner
     * @param repo Repository name
     * @param prNumber Pull request number
     * @param updates JSON object of fields to update
     */
    suspend fun updatePullRequest(
        owner: String,
        repo: String,
        prNumber: Int,
        updates: JsonObject
    ) {
        validateSegments(owner, repo)
        val url = pullUrl(owner, repo, prNumber)
        val response = trackedRequest {
            patch(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(updates)
            }
        }
        requireSuccess(response, "PATCH /repos/$owner/$repo/pulls/$prNumber")
    }

    /**
     * Create a pull request review comment (inline diff comment).
     * @param owner Repository owner
     * @param repo Repository name
     * @param prNumber Pull request number
     * @param body Comment body
     * @param commitId The SHA of the commit to comment on
     * @param path The relative path to the file to comment on
     * @param line The line number in the file to comment on
     * @return GitHubPullRequestReviewComment with the created comment
     */
    suspend fun createPullRequestReviewComment(
        owner: String,
        repo: String,
        prNumber: Int,
        body: String,
        commitId: String,
        path: String,
        line: Int,
        startLine: Int? = null,
    ): GitHubPullRequestReviewComment {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/pulls/$prNumber/comments"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                val params = buildJsonObject {
                    put("body", body)
                    put("commit_id", commitId)
                    put("path", path)
                    put("line", line)
                    put("side", NEW_SIDE)
                    if (startLine != null && startLine < line) {
                        // a ```suggestion block in the body then replaces start_line..line
                        put("start_line", startLine)
                        put("start_side", NEW_SIDE)
                    }
                }
                setBody(params)
            }
        }
        return requireSuccess(response, "POST /repos/$owner/$repo/pulls/$prNumber/comments").body()
    }

    /**
     * List all pull request review comments (inline diff comments), following pagination automatically.
     * @param owner Repository owner
     * @param repo Repository name
     * @param prNumber Pull request number
     * @return List of review comments
     */
    suspend fun listPullRequestReviewComments(
        owner: String,
        repo: String,
        prNumber: Int
    ): List<GitHubPullRequestReviewComment> {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/pulls/$prNumber/comments?per_page=$pageSize"
        return getAllPages(url, "GET /repos/$owner/$repo/pulls/$prNumber/comments")
    }

    /** Replaces the body of a pull request review comment. */
    suspend fun updatePullRequestReviewComment(
        owner: String,
        repo: String,
        commentId: Long,
        body: String,
    ) = patchBody(owner, repo, "pulls/comments/$commentId", body)

    /** Deletes an issue/PR conversation comment. */
    suspend fun deleteIssueComment(
        owner: String,
        repo: String,
        commentId: Long,
    ) {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/issues/comments/$commentId"
        requireSuccess(trackedRequest { delete(url) { configureAuth() } }, "DELETE /repos/$owner/$repo/issues/comments/$commentId")
    }

    /** Combined legacy commit status for [ref] (a commit SHA). */
    suspend fun fetchCombinedStatus(
        owner: String,
        repo: String,
        ref: String,
    ): GitHubCombinedStatus {
        validateSegments(owner, repo)
        return getJson("$baseUrl/repos/$owner/$repo/commits/$ref/status?per_page=$MAX_PER_PAGE")
    }

    /** Commits from [base] to [head], oldest first: unlike the pull request's list, paged past 250. */
    suspend fun fetchCompareCommits(
        owner: String,
        repo: String,
        base: String,
        head: String,
    ): List<GitHubCommit> {
        validateSegments(owner, repo)
        val label = "GET /repos/$owner/$repo/compare"
        val pages = getAllPagesRaw("$baseUrl/repos/$owner/$repo/compare/$base...$head?per_page=$MAX_PER_PAGE", label)
        return pages.flatMap { decodeBody<GitHubCompare>(it, label).commits }
    }

    /** Check runs (GitHub Actions and other check apps) for [ref] (a commit SHA), across all pages. */
    suspend fun fetchCheckRuns(
        owner: String,
        repo: String,
        ref: String,
    ): GitHubCheckRuns {
        validateSegments(owner, repo)
        val label = "GET /repos/$owner/$repo/commits/$ref/check-runs"
        val pages = getAllPagesRaw("$baseUrl/repos/$owner/$repo/commits/$ref/check-runs?per_page=$MAX_PER_PAGE", label)
        return GitHubCheckRuns(pages.flatMap { decodeBody<GitHubCheckRuns>(it, label).checkRuns })
    }

    /**
     * Toggle draft state. The REST `draft` field is ignored on update, so this goes through the GraphQL
     * `convertPullRequestToDraft` / `markPullRequestReadyForReview` mutations.
     */
    suspend fun setDraft(
        owner: String,
        repo: String,
        prNumber: Int,
        draft: Boolean,
    ) {
        val nodeId = checkNotNull(fetchPullRequest(owner, repo, prNumber).nodeId) {
            "GitHub PR $owner/$repo#$prNumber has no node_id; cannot change draft state"
        }
        val mutation = if (draft) "convertPullRequestToDraft" else "markPullRequestReadyForReview"
        graphql(
            mutation,
            "mutation(\$id: ID!) { $mutation(input: {pullRequestId: \$id}) { clientMutationId } }",
            buildJsonObject { put("id", nodeId) },
        )
    }

    /** Runs a GraphQL [query] with [variables] and returns its `data`; a response carrying `errors` fails. */
    suspend fun graphql(
        label: String,
        query: String,
        variables: JsonObject,
    ): JsonObject {
        val response = trackedRequest {
            post(graphqlUrl()) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("query", query)
                        put("variables", variables)
                    },
                )
            }
        }
        val requestLabel = "POST graphql $label"
        val body = requireSuccess(response, requestLabel).bodyAsText()
        val parsed = decodeBody<JsonObject>(body, requestLabel)
        check("errors" !in parsed) { "GitHub GraphQL $label failed: ${body.take(MAX_ERROR_BODY_LENGTH)}" }
        return parsed["data"] as? JsonObject ?: JsonObject(emptyMap())
    }

    /** `https://api.github.com` -> `.../graphql`; GitHub Enterprise `https://host/api/v3` -> `https://host/api/graphql`. */
    private fun graphqlUrl(): String = baseUrl.removeSuffix("/").removeSuffix("/v3") + "/graphql"

    private suspend inline fun <reified T> getJson(url: String): T =
        trackedRequest {
            get(url) {
                configureAuth()
                accept(ContentType.Application.Json)
            }.body()
        }

    /**
     * Fetch commit history for a specific file (used as blame fallback).
     * @param owner Repository owner
     * @param repo Repository name
     * @param path File path within the repository
     * @param ref Git ref to list commits from
     * @return List of commits that modified this file
     */
    suspend fun fetchFileCommits(
        owner: String,
        repo: String,
        path: String,
        ref: String,
    ): List<GitHubCommit> {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/commits?path=$path&sha=$ref&per_page=30"
        val result: List<GitHubCommit> = trackedRequest {
            get(url) {
                configureAuth()
                accept(ContentType.Application.Json)
            }.body()
        }
        return result
    }

    // close() is inherited from BaseProviderClient

    private companion object {
        /** New-file side of the diff, where review comments and suggestions land. */
        const val NEW_SIDE = "RIGHT"
    }

    private fun pullUrl(
        owner: String,
        repo: String,
        prNumber: Int,
    ): String = "$baseUrl/repos/$owner/$repo/pulls/$prNumber"
}
