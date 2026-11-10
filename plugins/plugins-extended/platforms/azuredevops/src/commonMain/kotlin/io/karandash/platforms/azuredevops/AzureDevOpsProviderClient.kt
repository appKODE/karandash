package io.karandash.platforms.azuredevops

import io.karandash.api.exception.ProviderApiException
import io.karandash.api.support.rethrowCancellation
import io.karandash.http.HttpAuth
import io.karandash.http.ProviderResponseCache
import io.karandash.pagination.DEFAULT_MAX_PAGES
import io.karandash.pagination.PaginationResult
import io.karandash.pagination.paginateByUrl
import io.karandash.providers.common.BaseProviderClient
import io.karandash.providers.common.jsonListIds
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.io.IOException

class AzureDevOpsProviderClient(
    httpClient: HttpClient? = null,
    private val token: String? = null,
    allowLocalhost: Boolean = false,
    logger: Logger = NoOpLogger,
    private val pageSize: Int = PAGE_SIZE,
    baseUrl: String = AzureDevOpsPlatformService.DEFAULT_BASE_URL,
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
    override val platformName: String = "AzureDevOps"

    // Azure DevOps requires format: Base64(":$PAT") - empty username with colon
    override fun credential(): HttpAuth? = token?.let { HttpAuth.Basic("", it) }

    /** Ids of the active PRs into [targetBranch], newest first, at most [limit]. */
    suspend fun listActivePullRequestIds(
        organization: String,
        project: String,
        repository: String,
        targetBranch: String,
        limit: Int,
    ): List<String> {
        val target = "refs/heads/$targetBranch".encodeURLParameter()
        val url = "$baseUrl/$organization/$project/_apis/git/repositories/$repository/pullrequests" +
            "?searchCriteria.status=active&searchCriteria.targetRefName=$target&${'$'}top=$limit&api-version=7.0"
        val body = cachedGetRaw(url, "GET $organization/$project/$repository/pullrequests?status=active")
        return jsonListIds(body, "pullRequestId", envelope = "value").take(limit)
    }

    /** The authenticated account's name (`GET {organization}/_apis/connectionData`). */
    internal suspend fun fetchCurrentUsername(organization: String): String? =
        getJson<AzureConnectionData>("$baseUrl/$organization/_apis/connectionData").username()

    /**
     * Pull requests in every status, newest first, created by [creatorId] when given and with [text] in the title or
     * description, at most [limit].
     */
    internal suspend fun searchPullRequests(
        organization: String,
        project: String,
        repository: String,
        creatorId: String?,
        text: String?,
        limit: Int,
    ): List<AzurePullRequestHit> {
        // limit: the API has no text filter, so text search reads one page of pageSize and filters it here;
        // older matches are missed, page through $skip if that matters.
        val top = if (text.isNullOrBlank()) limit.coerceIn(1, pageSize) else pageSize
        val creator = creatorId?.let { "&searchCriteria.creatorId=${it.encodeURLParameter()}" }.orEmpty()
        val url = "$baseUrl/$organization/$project/_apis/git/repositories/$repository/pullrequests" +
            "?searchCriteria.status=all$creator&${'$'}top=$top&api-version=7.0"
        return getJson<AzurePullRequestHits>(url).value.filter { it.matches(text) }.take(limit)
    }

    suspend fun fetchPullRequest(
        organization: String,
        project: String,
        repository: String,
        prId: String
    ): AzureDevOpsPullRequest {
        val url = "$baseUrl/$organization/$project/_apis/git/repositories/$repository/pullrequests/$prId?api-version=7.0"
        return getJson(url)
    }

    /** Statuses posted on the pull request (build validation, external checks), oldest first. */
    suspend fun fetchStatuses(
        organization: String,
        project: String,
        repository: String,
        prId: String
    ): List<AzureDevOpsPullRequestStatus> {
        val url = "$baseUrl/$organization/$project/_apis/git/repositories/$repository/pullRequests/$prId/statuses?api-version=7.0"
        val response: AzureDevOpsPullRequestStatusesResponse = getJson(url)
        return response.value
    }

    suspend fun fetchCommits(
        organization: String,
        project: String,
        repository: String,
        prId: String
    ): List<AzureDevOpsCommit> {
        val url = "$baseUrl/$organization/$project/_apis/git/repositories/$repository/pullrequests/$prId/commits" +
            "?${'$'}top=$pageSize&api-version=7.0"
        return paginate("commits of pull request $prId", url) { pageUrl ->
            val (body, next) = cachedGetPage(
                pageUrl,
                "GET $pageUrl",
                nextOf = { r -> r.headers[CONTINUATION_TOKEN_HEADER]?.let { "$url&continuationToken=$it" } },
            )
            PaginationResult(decodeBody<AzureDevOpsCommitsResponse>(body, "GET $pageUrl").value, next)
        }
    }

    suspend fun fetchFileCommits(
        organization: String,
        project: String,
        repository: String,
        path: String,
        ref: String
    ): List<AzureDevOpsCommit> {
        val url = "$baseUrl/$organization/$project/_apis/git/repositories/$repository/commits" +
            "?searchCriteria.itemPath=$path&searchCriteria.itemVersion.version=$ref&api-version=7.0"
        return getOrWarn<AzureDevOpsCommitsResponse>(url, "commits of $path")?.value.orEmpty()
    }

    /**
     * Files changed by the pull request: the latest iteration's changes compared with the target branch
     * (`pullRequests/{id}/iterations/{last}/changes`, following `nextSkip` page by page). Empty, with a warning,
     * when Azure DevOps does not answer.
     */
    suspend fun fetchChanges(
        organization: String,
        project: String,
        repository: String,
        prId: String
    ): List<AzureDevOpsChangeEntry> {
        val pullRequestUrl = "$baseUrl/$organization/$project/_apis/git/repositories/$repository/pullRequests/$prId"
        val iterations = getOrWarn<AzureDevOpsIterationsResponse>(
            "$pullRequestUrl/iterations?api-version=7.0",
            "iterations of pull request $prId",
        ) ?: return emptyList()
        val latest = iterations.value.maxOfOrNull { it.id } ?: return emptyList()
        val changesUrl = "$pullRequestUrl/iterations/$latest/changes?${'$'}top=$pageSize&api-version=7.0"
        return paginate("changes of pull request $prId", changesUrl) { pageUrl ->
            val page = getOrWarn<AzureDevOpsChangesResponse>(pageUrl, "changes of pull request $prId")
            val next = page?.nextSkip?.takeIf { it > 0 }?.let { "$changesUrl&${'$'}skip=$it" }
            PaginationResult(page?.changeEntries.orEmpty(), next)
        }
    }

    /**
     * Every page from [firstUrl] until [fetchPage] returns no next URL. Stops after the page cap with a warning that
     * later items are missing.
     */
    private suspend fun <T> paginate(
        what: String,
        firstUrl: String,
        fetchPage: suspend (url: String) -> PaginationResult<T>,
    ): List<T> = paginateByUrl(firstUrl, maxPages(DEFAULT_MAX_PAGES), warnTruncated("Azure DevOps $what"), fetchPage)

    private suspend inline fun <reified T> getJson(url: String): T = cachedGetAndDecode(url, "GET $url")

    /** [url]'s decoded body, or null with a warning when the request fails or the answer is not the expected JSON. */
    private suspend inline fun <reified T> getOrWarn(
        url: String,
        what: String,
    ): T? {
        val failure = try {
            return getJson<T>(url)
        } catch (e: ProviderApiException) {
            "HTTP ${e.statusCode}"
        } catch (e: IOException) {
            e.rethrowCancellation()
            e.message ?: e::class.simpleName
        } catch (e: IllegalStateException) {
            e.rethrowCancellation()
            // Azure DevOps answers an unauthenticated request with an HTML sign-in page.
            e.message ?: e::class.simpleName
        }
        logger.warn("Azure DevOps: could not fetch $what ($failure); continuing without it")
        return null
    }

    /** A blob's raw content as text, or null when Azure DevOps does not return it. */
    suspend fun fetchBlob(
        organization: String,
        project: String,
        repository: String,
        objectId: String
    ): String? {
        val url = "$baseUrl/$organization/$project/_apis/git/repositories/$repository/blobs/$objectId" +
            "?${'$'}format=octetstream&api-version=7.0"
        val response = trackedRequest { get(url) { configureAuth() } }
        return response.bodyAsText().takeIf { response.status.isSuccess() }
    }

    /**
     * Raw content of [path] at [ref] (`GET .../items?path=` with a commit version for a full sha, else a branch),
     * or null when missing, binary or over the cap.
     */
    suspend fun fetchFileContent(
        organization: String,
        project: String,
        repository: String,
        ref: String,
        path: String,
    ): String? {
        val versionType = if (FULL_SHA.matches(ref)) "commit" else "branch"
        val url = "$baseUrl/$organization/$project/_apis/git/repositories/$repository/items"
        return getFileTextOrNull(url, "content of $path at $ref") {
            parameter("path", path)
            parameter("versionDescriptor.version", ref.removePrefix("refs/heads/"))
            parameter("versionDescriptor.versionType", versionType)
            parameter("${'$'}format", "octetStream")
            parameter("api-version", "7.0")
        }
    }

    private companion object {
        val FULL_SHA = Regex("^[0-9a-fA-F]{40}$")

        /** Items per page; within the `${'$'}top` limit of every Azure DevOps list endpoint used here. */
        const val PAGE_SIZE = 100
        const val CONTINUATION_TOKEN_HEADER = "x-ms-continuationtoken"
    }
}
