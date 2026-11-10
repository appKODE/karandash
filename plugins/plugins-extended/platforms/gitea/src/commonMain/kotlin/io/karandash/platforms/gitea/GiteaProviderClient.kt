package io.karandash.platforms.gitea

import io.karandash.http.HttpAuth
import io.karandash.http.ProviderResponseCache
import io.karandash.pagination.DEFAULT_MAX_PAGES
import io.karandash.pagination.PaginationResult
import io.karandash.pagination.paginateByPageNumber
import io.karandash.providers.common.BaseProviderClient
import io.karandash.providers.common.jsonListIds
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Client for interacting with Gitea REST API
 */
class GiteaProviderClient(
    baseUrl: String,
    httpClient: HttpClient? = null,
    private val token: String? = null,
    allowLocalhost: Boolean = false,
    logger: Logger = NoOpLogger,
    private val pageSize: Int = PAGE_SIZE,
    responseCache: ProviderResponseCache? = null,
    engine: HttpClientEngine? = null,
) : BaseProviderClient(
        baseUrl = baseUrl,
        httpClient = httpClient,
        allowLocalhost = allowLocalhost,
        logger = logger,
        responseCache = responseCache,
        engine = engine,
    ) {
    override val platformName: String = PLATFORM_NAME

    override fun credential(): HttpAuth? = token?.let { HttpAuth.CustomHeader(HttpHeaders.Authorization, "token $it") }

    /**
     * Every page of a list endpoint. Asks for the next page while the current one is full or Gitea's `Link` header
     * names a next one (Gitea may serve fewer items than `limit` asks for). Warns when the page cap cuts the list.
     */
    private suspend inline fun <reified T> getAllPages(firstUrl: String): List<T> {
        val onTruncated = warnTruncated("Gitea GET ${firstUrl.removePrefix(baseUrl)}")
        return paginateByPageNumber(maxPages(DEFAULT_MAX_PAGES), onTruncated) { page ->
            val url = "$firstUrl&page=$page"
            val (body, link) = cachedGetPage(url, "GET ${url.removePrefix(baseUrl)}")
            val pageItems = decodeBody<List<T>>(body, "GET ${url.removePrefix(baseUrl)}")
            val more = pageItems.isNotEmpty() && (pageItems.size >= pageSize || link != null)
            PaginationResult(pageItems, if (more) "${page + 1}" else null)
        }
    }

    /**
     * Numbers of the open PRs into [base], most recently updated first, at most [limit]. The list API has no base
     * filter, so it reads one page of [pageSize] open PRs and filters on `base.ref`.
     */
    suspend fun listOpenPullRequestNumbers(
        owner: String,
        repo: String,
        base: String,
        limit: Int,
    ): List<String> {
        val url = "$baseUrl/api/v1/repos/$owner/$repo/pulls?state=open&sort=recentupdate&limit=$pageSize"
        val body = cachedGetRaw(url, "GET /repos/$owner/$repo/pulls?state=open")
        return jsonListIds(body, "number") { pr ->
            ((pr["base"] as? JsonObject)?.get("ref") as? JsonPrimitive)?.contentOrNull == base
        }.take(limit)
    }

    /**
     * Fetch a pull request by owner, repo, and PR number
     * @param owner The repository owner
     * @param repo The repository name
     * @param prNumber The pull request number
     * @return The Gitea pull request
     */
    suspend fun fetchPullRequest(
        owner: String,
        repo: String,
        prNumber: String
    ): GiteaPullRequest {
        val url = "$baseUrl/api/v1/repos/$owner/$repo/pulls/$prNumber"

        return cachedGetAndDecode(url, "GET $url")
    }

    /**
     * Fetch commits for a pull request
     * @param owner The repository owner
     * @param repo The repository name
     * @param prNumber The pull request number
     * @return List of commits
     */
    suspend fun fetchPullRequestCommits(
        owner: String,
        repo: String,
        prNumber: String
    ): List<GiteaCommit> = getAllPages("$baseUrl/api/v1/repos/$owner/$repo/pulls/$prNumber/commits?limit=$pageSize")

    /** Combined commit status for [sha]. */
    suspend fun fetchCombinedStatus(
        owner: String,
        repo: String,
        sha: String
    ): GiteaCombinedStatus = cachedGetAndDecode("$baseUrl/api/v1/repos/$owner/$repo/commits/$sha/status", "GET commit status of $sha")

    /** The pull request's unified diff (`git diff` text), or null when Gitea does not answer with one. */
    suspend fun fetchPullRequestDiff(
        owner: String,
        repo: String,
        prNumber: String
    ): String? {
        val response = trackedRequest { get("$baseUrl/api/v1/repos/$owner/$repo/pulls/$prNumber.diff") { configureAuth() } }
        return response.bodyAsText().takeIf { response.status.isSuccess() }
    }

    /**
     * Fetch file changes for a pull request
     * @param owner The repository owner
     * @param repo The repository name
     * @param prNumber The pull request number
     * @return List of file changes
     */
    suspend fun fetchPullRequestFiles(
        owner: String,
        repo: String,
        prNumber: String
    ): List<GiteaFile> = getAllPages("$baseUrl/api/v1/repos/$owner/$repo/pulls/$prNumber/files?limit=$pageSize")

    /**
     * Fetch reviews for a pull request
     * @param owner The repository owner
     * @param repo The repository name
     * @param prNumber The pull request number
     * @return List of reviews
     */
    suspend fun fetchPullRequestReviews(
        owner: String,
        repo: String,
        prNumber: String
    ): List<GiteaReview> = getAllPages("$baseUrl/api/v1/repos/$owner/$repo/pulls/$prNumber/reviews?limit=$pageSize")

    /**
     * Fetch commit history for a specific file (used as blame fallback).
     * @param owner The repository owner
     * @param repo The repository name
     * @param path File path within the repository
     * @param ref Git ref to list commits from
     * @return List of commits that modified this file
     */
    suspend fun fetchFileCommits(
        owner: String,
        repo: String,
        path: String,
        ref: String
    ): List<GiteaCommit> {
        val url = "$baseUrl/api/v1/repos/$owner/$repo/commits?path=$path&sha=$ref&limit=30"

        return cachedGetAndDecode(url, "GET $url")
    }

    /** Raw content of [path] at [ref] (`GET /repos/{owner}/{repo}/raw/{path}?ref=`), or null when missing, binary or over the cap. */
    suspend fun fetchFileContent(
        owner: String,
        repo: String,
        ref: String,
        path: String,
    ): String? =
        getFileTextOrNull("$baseUrl/api/v1/repos/$owner/$repo/raw/${path.encodeURLPath()}", "content of $path at $ref") {
            parameter("ref", ref)
        }

    /** The pull request's conversation comments; Gitea's issue comment list takes no `page`/`limit`, so one GET returns all. */
    internal suspend fun listIssueComments(
        owner: String,
        repo: String,
        prNumber: Int,
    ): List<GiteaComment> {
        val path = "/repos/$owner/$repo/issues/$prNumber/comments"
        return cachedGetAndDecode("$baseUrl/api/v1$path", "GET $path")
    }

    /** Reactions on the pull request itself (`GET /repos/{owner}/{repo}/issues/{index}/reactions`), all pages. */
    internal suspend fun listIssueReactions(
        owner: String,
        repo: String,
        prNumber: Int,
    ): List<GiteaReaction> = getAllPages("$baseUrl/api/v1/repos/$owner/$repo/issues/$prNumber/reactions?limit=$pageSize")

    /**
     * Pull requests in any state matching [text] (Gitea's `q` keyword search) and/or opened by [author], at most [limit]
     * (`GET /repos/{owner}/{repo}/issues?type=pulls&state=all`; Gitea caps one page at its `MAX_RESPONSE_ITEMS`).
     */
    internal suspend fun searchPullRequests(
        owner: String,
        repo: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<GiteaIssue> {
        val url = buildString {
            append("$baseUrl/api/v1/repos/$owner/$repo/issues?type=pulls&state=all&limit=$limit")
            text?.takeIf { it.isNotBlank() }?.let { append("&q=").append(it.encodeURLParameter()) }
            author?.takeIf { it.isNotBlank() }?.let { append("&created_by=").append(it.encodeURLParameter()) }
        }
        return cachedGetAndDecode<List<GiteaIssue>>(url, "GET /repos/$owner/$repo/issues?type=pulls").take(limit)
    }

    private companion object {
        const val PLATFORM_NAME = "Gitea"

        /** Gitea's default `MAX_RESPONSE_ITEMS`. */
        const val PAGE_SIZE = 50
    }
}
