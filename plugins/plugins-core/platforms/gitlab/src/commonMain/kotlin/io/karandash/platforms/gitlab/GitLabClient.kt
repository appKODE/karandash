@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gitlab

import io.karandash.api.exception.ProviderApiException
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.support.rethrowCancellation
import io.karandash.cache.CachedValue
import io.karandash.http.HttpAuth
import io.karandash.http.PathSegmentEncoder
import io.karandash.http.ProviderResponseCache
import io.karandash.pagination.PaginationResult
import io.karandash.pagination.paginateByPageNumber
import io.karandash.providers.common.AdapterHelpers
import io.karandash.providers.common.BaseProviderClient
import io.karandash.providers.common.jsonListIds
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.utils.takeWhole
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * HTTP client for interacting with GitLab API
 */
class GitLabClient(
    baseUrl: String = "https://gitlab.com",
    private val token: String? = null,
    httpClient: HttpClient? = null,
    allowLocalhost: Boolean = false,
    logger: Logger = NoOpLogger,
    private val approveToken: String? = null,
    responseCache: ProviderResponseCache? = null,
    internal val pageSize: Int = MAX_PER_PAGE,
    engine: HttpClientEngine? = null,
    private val jobToken: Boolean = false,
) : BaseProviderClient(
        baseUrl = baseUrl,
        httpClient = httpClient,
        allowLocalhost = allowLocalhost,
        logger = logger,
        responseCache = responseCache,
        engine = engine,
    ) {
    override val platformName: String = PLATFORM_NAME

    override fun credential(): HttpAuth? = token?.let { HttpAuth.CustomHeader(if (jobToken) JOB_TOKEN_HEADER else AUTH_HEADER, it) }

    override val credentialHeaderNames: Set<String> get() = setOf(AUTH_HEADER, JOB_TOKEN_HEADER)

    private val cachedUsername = MutableStateFlow<CachedValue<String?>>(CachedValue.Unresolved)

    internal companion object {
        private const val AUTH_HEADER = "PRIVATE-TOKEN"
        private const val JOB_TOKEN_HEADER = "JOB-TOKEN"
        private const val PLATFORM_NAME = "GitLab"
        private const val APPROVE_TOKEN_REQUIRED = "Approve token required"

        private const val HEADER_NEXT_PAGE = "x-next-page"
    }

    /**
     * Fetch a merge request by project ID and MR IID
     * @param projectId The project ID (can be numeric or "group/project" format)
     * @param mrIid The merge request IID (internal ID)
     * @return GitLabMergeRequest object
     */
    suspend fun fetchMergeRequest(
        projectId: String,
        mrIid: Int
    ): GitLabMergeRequest {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid"
        return cachedGetAndDecode(url, "GET /projects/$projectId/merge_requests/$mrIid")
    }

    /** IIDs of the open MRs targeting [targetBranch], most recently updated first, at most [limit]. */
    suspend fun listOpenMergeRequestIids(
        projectId: String,
        targetBranch: String,
        limit: Int,
    ): List<String> {
        val url = "$baseUrl/api/v4/projects/${encodeProjectId(projectId)}/merge_requests?state=opened" +
            "&target_branch=${targetBranch.encodeURLParameter()}&order_by=updated_at&per_page=$limit"
        return jsonListIds(cachedGetRaw(url, "GET /projects/$projectId/merge_requests?state=opened"), "iid").take(limit)
    }

    /** MRs in any state matching [text] and/or by [author], most recently updated first, at most [limit]. */
    suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit> {
        val url = "$baseUrl/api/v4/projects/${encodeProjectId(projectId)}/merge_requests?" + gitLabSearchQuery(text, author, limit)
        return parseGitLabSearchHits(cachedGetRaw(url, "GET /projects/$projectId/merge_requests?search")).take(limit)
    }

    /**
     * Jobs of pipeline [pipelineId], all pages. An API error or an unreadable response yields null
     * (job-level CI status unknown) with a warning.
     */
    suspend fun fetchPipelineJobs(
        projectId: String,
        pipelineId: Long
    ): List<GitLabJob>? {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/pipelines/$pipelineId/jobs"
        return try {
            paginateGet<GitLabJob>(url, "GET /projects/$projectId/pipelines/$pipelineId/jobs")
        } catch (e: ProviderApiException) {
            logger.warn("GitLab jobs unavailable for pipeline $pipelineId (${e.statusCode}); job-level CI status unknown")
            null
        } catch (e: IllegalStateException) {
            e.rethrowCancellation()
            logger.warn("GitLab jobs response for pipeline $pipelineId unreadable (${e.message}); job-level CI status unknown")
            null
        }
    }

    /**
     * Fetch who approved a merge request (`GET .../merge_requests/:iid/approvals`). The MR resource itself
     * carries no `approved_by`. An API error (e.g. approvals unavailable to this token) or an unreadable
     * response yields an empty list.
     */
    suspend fun fetchApprovals(
        projectId: String,
        mrIid: Int
    ): List<GitLabApprover> {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid/approvals"
        return try {
            cachedGetAndDecode<GitLabApprovals>(url, "GET /projects/$projectId/merge_requests/$mrIid/approvals").approvedBy
        } catch (e: ProviderApiException) {
            logger.warn("GitLab approvals unavailable for MR !$mrIid (${e.statusCode}); treating as no approvals")
            emptyList()
        } catch (e: IllegalStateException) {
            e.rethrowCancellation() // CancellationException is an IllegalStateException
            logger.warn("GitLab approvals response for MR !$mrIid unreadable (${e.message}); treating as no approvals")
            emptyList()
        }
    }

    /**
     * Fetch commits for a merge request
     * @param projectId The project ID
     * @param mrIid The merge request IID
     * @return List of GitLabCommit objects
     */
    suspend fun fetchCommits(
        projectId: String,
        mrIid: Int
    ): List<GitLabCommit> {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid/commits"
        return paginateGet(url, "GET /projects/$projectId/merge_requests/$mrIid/commits")
    }

    /**
     * Fetch changes (diffs) for a merge request.
     *
     * Uses the `/changes?access_raw_diffs=true` endpoint, which bypasses the database-stored
     * (potentially truncated) diffs and fetches directly from Gitaly, which returns complete
     * diff content for large MRs where the stored diffs may be truncated.
     *
     * @param projectId The project ID
     * @param mrIid The merge request IID
     * @return List of GitLabDiff objects
     */
    suspend fun fetchChanges(
        projectId: String,
        mrIid: Int
    ): List<GitLabDiff> {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid/changes?access_raw_diffs=true"
        val payload: GitLabMergeRequestWithChanges =
            streamGetAndDecode(url, "GET /projects/$projectId/merge_requests/$mrIid/changes")
        if (payload.overflow) {
            logger.warn(
                "GitLab truncated the diff of MR !$mrIid at ${payload.changes.size} files (instance diff limit); " +
                    "files past it are not validated",
            )
        }
        return payload.changes
    }

    /**
     * Post a note (comment) on a merge request
     * @param projectId The project ID
     * @param mrIid The merge request IID
     * @param body The comment body
     * @return GitLabNote object with the created note
     */
    suspend fun postNote(
        projectId: String,
        mrIid: Int,
        body: String
    ): GitLabNote {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid/notes"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("body" to body))
            }
        }
        return decodeResponse(response, "POST /projects/$encodedProjectId/merge_requests/$mrIid/notes")
    }

    /**
     * Update an existing note on a merge request
     * @param projectId The project ID
     * @param mrIid The merge request IID
     * @param noteId The note ID to update
     * @param body The new comment body
     */
    suspend fun updateNote(
        projectId: String,
        mrIid: Int,
        noteId: Long,
        body: String
    ) {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid/notes/$noteId"
        val response = trackedRequest {
            put(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("body" to body))
            }
        }
        ensureSuccess(response, "PUT /projects/$encodedProjectId/merge_requests/$mrIid/notes/$noteId")
    }

    /**
     * List all notes on a merge request, paginating through all pages.
     *
     * Uses `per_page=100` (GitLab max) and follows the `x-next-page` response header.
     * Stops when no next page is indicated or after [MAX_PAGES] pages (safety cap).
     *
     * @param projectId The project ID
     * @param mrIid The merge request IID
     * @return List of GitLabNote objects across all pages
     */
    suspend fun listNotes(
        projectId: String,
        mrIid: Int
    ): List<GitLabNote> {
        val encodedProjectId = encodeProjectId(projectId)
        val baseNotesUrl = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid/notes"
        val requestLabel = "GET /projects/$encodedProjectId/merge_requests/$mrIid/notes"
        return paginateGet(baseNotesUrl, requestLabel)
    }

    /**
     * Delete a note on a merge request
     * @param projectId The project ID
     * @param mrIid The merge request IID
     * @param noteId The note ID to delete
     */
    suspend fun deleteNote(
        projectId: String,
        mrIid: Int,
        noteId: Long
    ) {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid/notes/$noteId"
        val response = trackedRequest {
            delete(url) { configureAuth() }
        }
        logger.debug("deleteNote: response object received, noteId=$noteId status=${response.status.value}")
        ensureSuccess(response, "DELETE /projects/$encodedProjectId/merge_requests/$mrIid/notes/$noteId")
        logger.debug("deleteNote: complete, noteId=$noteId")
    }

    /**
     * Find all note IDs on a merge request whose body contains the given marker.
     *
     * Unlike the single-match approach used by [listNotes] + `.find()`, this returns
     * ALL matching note IDs, enabling deduplication of header comments.
     *
     * @param projectId The project ID
     * @param mrIid The merge request IID
     * @param marker The marker string to search for in note bodies
     * @return List of matching note IDs as strings
     */
    suspend fun findAllNotesWithMarker(
        projectId: String,
        mrIid: Int,
        marker: String,
    ): List<String> {
        val notes = listNotes(projectId, mrIid)
        return notes
            .filter { !it.system && AdapterHelpers.isSummaryComment(it.body, marker) }
            .map { it.id.toString() }
    }

    /**
     * Update a merge request (used for label changes, draft toggling, etc.)
     * @param projectId The project ID
     * @param mrIid The merge request IID
     * @param updates JSON object of fields to update
     */
    suspend fun updateMergeRequest(
        projectId: String,
        mrIid: Int,
        updates: JsonObject
    ) {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid"
        val response = trackedRequest {
            put(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(updates)
            }
        }
        ensureSuccess(response, "PUT /projects/$encodedProjectId/merge_requests/$mrIid")
    }

    /**
     * Merge a merge request (`PUT /merge_requests/:iid/merge`).
     * @param body Merge parameters (`squash`, `should_remove_source_branch`, `merge_commit_message`)
     */
    suspend fun mergeMergeRequest(
        projectId: String,
        mrIid: Int,
        body: JsonObject,
    ) {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid/merge"
        val response = trackedRequest {
            put(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        ensureSuccess(response, "PUT /projects/$encodedProjectId/merge_requests/$mrIid/merge")
    }

    /**
     * Fetch the username of the currently authenticated user.
     * Uses the approve token if configured (since approve/unapprove use that token).
     * Result is cached after the first call. Returns null if the API call fails.
     */
    suspend fun fetchCurrentUsername(): String? {
        val current = cachedUsername.value
        if (current is CachedValue.Resolved) return current.value
        val resolved = try {
            val url = "$baseUrl/api/v4/user"
            val effectiveToken = approveToken ?: error(APPROVE_TOKEN_REQUIRED)
            val response = trackedRequest {
                get(url) { header(AUTH_HEADER, effectiveToken) }
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                null
            } else {
                val parsed: GitLabUser = json.decodeFromString(body)
                parsed.username
            }
        } catch (ignored: Exception) {
            // boundary: platform API call; approval lookup is best-effort
            ignored.rethrowCancellation()
            logger.warn("GitLab fetchCurrentUsername failed (${ignored.message ?: ignored::class.simpleName})")
            null
        }
        cachedUsername.value = CachedValue.Resolved(resolved)
        return resolved
    }

    /**
     * Check if the current authenticated user has already approved the given MR.
     * Returns false if the check fails (graceful degradation).
     */
    suspend fun isApprovedByCurrentUser(
        projectId: String,
        mrIid: Int
    ): Boolean {
        val username = fetchCurrentUsername() ?: return false
        return try {
            fetchApprovals(projectId, mrIid).any { it.user.username == username }
        } catch (ignored: Exception) {
            // boundary: platform API call; approval lookup is best-effort
            ignored.rethrowCancellation()
            logger.warn("GitLab isApprovedByCurrentUser failed (${ignored.message ?: ignored::class.simpleName})")
            false
        }
    }

    /**
     * Approve a merge request.
     * @param projectId The project ID (can be numeric or "group/project" format)
     * @param mrIid The merge request IID
     */
    suspend fun approveMergeRequest(
        projectId: String,
        mrIid: Int,
    ) {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid/approve"
        val effectiveToken = approveToken ?: error(APPROVE_TOKEN_REQUIRED)
        val response = trackedRequest {
            post(url) { header(AUTH_HEADER, effectiveToken) }
        }
        ensureSuccess(response, "POST /projects/$encodedProjectId/merge_requests/$mrIid/approve")
    }

    /**
     * Unapprove a merge request.
     *
     * A 404 response is treated as "already not approved" and silently ignored,
     * because GitLab returns 404 when the MR has no current approval from the
     * authenticated user (or when a project-level token lacks approve scope).
     *
     * @param projectId The project ID (can be numeric or "group/project" format)
     * @param mrIid The merge request IID
     */
    suspend fun unapproveMergeRequest(
        projectId: String,
        mrIid: Int,
    ) {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/merge_requests/$mrIid/unapprove"
        val effectiveToken = approveToken ?: error(APPROVE_TOKEN_REQUIRED)
        val response = trackedRequest {
            post(url) { header(AUTH_HEADER, effectiveToken) }
        }
        if (response.status.value == HTTP_NOT_FOUND) {
            logger.debug("Unapprove returned 404 for project=$projectId MR=$mrIid — treating as already not approved")
            return
        }
        ensureSuccess(response, "POST /projects/$encodedProjectId/merge_requests/$mrIid/unapprove")
    }

    /**
     * Fetch blame data for a file at a specific ref.
     * @param projectId The project ID (can be numeric or "group/project" format)
     * @param filePath The file path within the repository
     * @param ref Git ref (branch, tag, or commit SHA) to blame against
     * @return List of GitLabBlameEntry objects
     */
    suspend fun fetchBlame(
        projectId: String,
        filePath: String,
        ref: String,
    ): List<GitLabBlameEntry> {
        val encodedProjectId = encodeProjectId(projectId)
        val url = "$baseUrl/api/v4/projects/$encodedProjectId/repository/files/${filePath.encodeURLParameter()}/blame"
        val response = trackedRequest {
            get(url) {
                configureAuth()
                parameter("ref", ref)
            }
        }
        return decodeResponse(response, "GET /projects/$projectId/repository/files/$filePath/blame")
    }

    /**
     * Raw content of [filePath] at [ref] (`GET /projects/:id/repository/files/:path/raw`), or null when the file
     * does not exist at [ref], is binary or is over the cap ([getFileTextOrNull]).
     */
    suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        filePath: String,
    ): String? =
        getFileTextOrNull(
            "$baseUrl/api/v4/projects/${encodeProjectId(projectId)}/repository/files/${filePath.encodeURLParameter()}/raw",
            "content of $filePath at $ref",
        ) { parameter("ref", ref) }

    /**
     * Paginate through a GitLab list endpoint, accumulating all items across pages.
     *
     * Requests `per_page=`[pageSize] and follows the `x-next-page` response header. Stops when a page holds
     * fewer than [pageSize] items, the header is absent/empty, or [maxPages] pages have been fetched - the
     * last case warns that later items are missing.
     *
     * @param baseUrl The endpoint URL (without pagination query parameters)
     * @param requestLabel Human-readable label for error messages
     * @return Accumulated list of all items across all pages
     */
    internal suspend inline fun <reified T> paginateGet(
        baseUrl: String,
        requestLabel: String,
    ): List<T> =
        paginateByPageNumber(maxPages(), warnTruncated("GitLab $requestLabel")) { page ->
            val response = trackedRequest {
                get(baseUrl) {
                    configureAuth()
                    parameter("per_page", pageSize)
                    parameter("page", page)
                }
            }
            val rawBody = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw ProviderApiException(
                    statusCode = response.status.value,
                    statusDescription = response.status.description,
                    platformName = PLATFORM_NAME,
                    requestLabel = requestLabel,
                    responseBody = rawBody.compactForLog(),
                )
            }
            val pageItems: List<T> = decodeBody(rawBody, requestLabel)
            logger.debug("$requestLabel: page $page returned ${pageItems.size} item(s)")
            val nextPage = response.headers[HEADER_NEXT_PAGE]?.takeIf { pageItems.size >= pageSize && it.isNotBlank() }
            PaginationResult(pageItems, nextPage)
        }

    /**
     * Encode project ID for use in URLs (handles "group/project" format)
     */
    internal fun encodeProjectId(projectId: String): String = PathSegmentEncoder.encode(projectId)

    /** A request on this client's connection, for [GitLabDiscussionsClient]. */
    internal suspend fun <T> send(block: suspend HttpClient.() -> T): T = trackedRequest(block)

    /** Adds this client's auth header to [builder], for [GitLabDiscussionsClient]. */
    internal fun authorize(builder: HttpRequestBuilder) = builder.configureAuth()

    internal suspend inline fun <reified T> decodeResponse(
        response: HttpResponse,
        requestLabel: String
    ): T {
        val rawBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw ProviderApiException(
                statusCode = response.status.value,
                statusDescription = response.status.description,
                platformName = PLATFORM_NAME,
                requestLabel = requestLabel,
                responseBody = rawBody.compactForLog(),
            )
        }
        return decodeBody(rawBody, requestLabel)
    }

    /** [requireSuccess] quoting the error body compacted ([compactForLog]); for [GitLabDiscussionsClient] too. */
    internal suspend fun ensureSuccess(
        response: HttpResponse,
        requestLabel: String
    ) {
        requireSuccess(response, requestLabel) { it.compactForLog() }
    }

    internal fun String.compactForLog(maxLength: Int = 300): String {
        val compact = trim().replace('\n', ' ').replace('\r', ' ')
        return if (compact.length <= maxLength) compact else compact.takeWhole(maxLength) + "..."
    }

    // close() is inherited from BaseProviderClient
}

@kotlinx.serialization.Serializable
private data class GitLabMergeRequestWithChanges(
    val changes: List<GitLabDiff>,
    /** GitLab sets this when the MR has more files than the instance's diff limit; `changes` is then cut short. */
    val overflow: Boolean = false,
)
