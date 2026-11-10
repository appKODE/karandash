package io.karandash.platforms.github

import io.karandash.api.finding.InlineCommentData
import io.karandash.api.support.rethrowCancellation
import io.karandash.cache.CachedValue
import io.karandash.http.ProviderResponseCache
import io.karandash.providers.common.BaseProviderClient
import io.karandash.providers.common.PathSegmentEncoder
import io.karandash.sdk.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The reviewer, label and approval half of [GitHubClient]: calls that only need the shared request plumbing of
 * [BaseProviderClient]. Split out to keep [GitHubClient] within the class size limit.
 */
abstract class GitHubClientBase(
    baseUrl: String,
    httpClient: HttpClient?,
    allowLocalhost: Boolean,
    logger: Logger,
    responseCache: ProviderResponseCache?,
    engine: HttpClientEngine?,
) : BaseProviderClient(
        baseUrl = baseUrl,
        httpClient = httpClient,
        allowLocalhost = allowLocalhost,
        logger = logger,
        responseCache = responseCache,
        engine = engine,
    ) {
    override val platformName: String = PLATFORM_NAME

    private val cachedUsername = MutableStateFlow<CachedValue<String?>>(CachedValue.Unresolved)

    /** Reviews of a pull request, all pages. */
    abstract suspend fun fetchReviews(
        owner: String,
        repo: String,
        prNumber: Int,
    ): List<GitHubReview>

    protected fun validateSegments(
        owner: String,
        repo: String,
    ) {
        PathSegmentEncoder.encode(owner)
        PathSegmentEncoder.encode(repo)
    }

    /**
     * Raw content of [path] at [ref] (`GET /repos/{owner}/{repo}/contents/{path}` with the raw media type), or null
     * when missing, binary or over the cap.
     */
    suspend fun fetchFileContent(
        owner: String,
        repo: String,
        ref: String,
        path: String,
    ): String? {
        validateSegments(owner, repo)
        return getFileTextOrNull("$baseUrl/repos/$owner/$repo/contents/${path.encodeURLPath()}", "content of $path at $ref") {
            headers[HttpHeaders.Accept] = "application/vnd.github.raw"
            parameter("ref", ref)
        }
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
            get("$baseUrl/repos/$owner/$repo/collaborators/${PathSegmentEncoder.encode(username)}/permission") {
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
            val user: GitHubUser = trackedRequest {
                get("$baseUrl/user") {
                    configureAuth()
                    accept(ContentType.Application.Json)
                }.body()
            }
            user.login
        } catch (ignored: Exception) {
            // boundary: platform API call; approval lookup is best-effort
            ignored.rethrowCancellation()
            logger.warn("GitHub fetchCurrentUsername failed (${ignored.message ?: ignored::class.simpleName})")
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
        prNumber: Int
    ): Boolean {
        val username = fetchCurrentUsername() ?: return false
        return try {
            val reviews = fetchReviews(owner, repo, prNumber)
            reviews.any { it.state == REVIEW_STATE_APPROVED && it.user.login == username }
        } catch (ignored: Exception) {
            // boundary: platform API call; approval lookup is best-effort
            ignored.rethrowCancellation()
            logger.warn("GitHub isApprovedByCurrentUser failed (${ignored.message ?: ignored::class.simpleName})")
            false
        }
    }

    /**
     * Create an approval review on a pull request.
     * @param owner Repository owner
     * @param repo Repository name
     * @param prNumber Pull request number
     */
    suspend fun createApprovalReview(
        owner: String,
        repo: String,
        prNumber: Int,
    ) {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/pulls/$prNumber/reviews"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("event" to "APPROVE"))
            }
        }
        requireSuccess(response, "POST /repos/$owner/$repo/pulls/$prNumber/reviews")
    }

    /**
     * Dismiss the authenticated user's latest approval review on a pull request.
     * Fetches all reviews, finds the last APPROVED one, then dismisses it.
     * @param owner Repository owner
     * @param repo Repository name
     * @param prNumber Pull request number
     */
    suspend fun dismissApprovalReview(
        owner: String,
        repo: String,
        prNumber: Int,
    ) {
        validateSegments(owner, repo)
        val reviews = fetchReviews(owner, repo, prNumber)
        val currentUser = fetchCurrentUsername()
        val approvalReview = if (currentUser != null) {
            reviews.lastOrNull { it.state == REVIEW_STATE_APPROVED && it.user.login == currentUser }
        } else {
            reviews.lastOrNull { it.state == REVIEW_STATE_APPROVED }
        }
        val reviewId = approvalReview?.id ?: return
        val url = "$baseUrl/repos/$owner/$repo/pulls/$prNumber/reviews/$reviewId/dismissals"
        val response = trackedRequest {
            put(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("message" to "Auto-unapproved by Karandash"))
            }
        }
        requireSuccess(response, "PUT /repos/$owner/$repo/pulls/$prNumber/reviews/$reviewId/dismissals")
    }

    /**
     * Add labels to an issue/PR
     * @param owner Repository owner
     * @param repo Repository name
     * @param issueNumber Issue/PR number
     * @param labels Labels to add
     */
    suspend fun addLabels(
        owner: String,
        repo: String,
        issueNumber: Int,
        labels: List<String>
    ) {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/issues/$issueNumber/labels"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("labels" to labels))
            }
        }
        requireSuccess(response, "POST /repos/$owner/$repo/issues/$issueNumber/labels")
    }

    /**
     * Remove a label from an issue/PR
     * @param owner Repository owner
     * @param repo Repository name
     * @param issueNumber Issue/PR number
     * @param label Label name to remove
     */
    suspend fun removeLabel(
        owner: String,
        repo: String,
        issueNumber: Int,
        label: String
    ) {
        validateSegments(owner, repo)
        val encodedLabel = label.replace(" ", "%20")
        val url = "$baseUrl/repos/$owner/$repo/issues/$issueNumber/labels/$encodedLabel"
        val response = trackedRequest {
            delete(url) { configureAuth() }
        }
        requireSuccess(response, "DELETE /repos/$owner/$repo/issues/$issueNumber/labels/$encodedLabel")
    }

    /** Request reviews from [reviewers] (user logins) on a pull request. */
    suspend fun requestReviewers(
        owner: String,
        repo: String,
        prNumber: Int,
        reviewers: List<String>,
    ) {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/pulls/$prNumber/requested_reviewers"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("reviewers" to reviewers))
            }
        }
        requireSuccess(response, "POST /repos/$owner/$repo/pulls/$prNumber/requested_reviewers")
    }

    /** Withdraw review requests from [reviewers] (user logins) on a pull request. */
    suspend fun removeRequestedReviewers(
        owner: String,
        repo: String,
        prNumber: Int,
        reviewers: List<String>,
    ) {
        validateSegments(owner, repo)
        val url = "$baseUrl/repos/$owner/$repo/pulls/$prNumber/requested_reviewers"
        val response = trackedRequest {
            delete(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(mapOf("reviewers" to reviewers))
            }
        }
        requireSuccess(response, "DELETE /repos/$owner/$repo/pulls/$prNumber/requested_reviewers")
    }

    /**
     * Post [comments] as one submitted `COMMENT` review on [commitId] (one notification instead of one per comment);
     * each comment sits on the new-file side, a range when its start line is before its line.
     */
    suspend fun createCommentReview(
        owner: String,
        repo: String,
        prNumber: Int,
        commitId: String,
        comments: List<InlineCommentData>,
    ) {
        validateSegments(owner, repo)
        val params = buildJsonObject {
            put("commit_id", commitId)
            put("event", "COMMENT")
            putJsonArray("comments") { comments.forEach { addJsonObject { putReviewComment(it) } } }
        }
        val response = trackedRequest {
            post("$baseUrl/repos/$owner/$repo/pulls/$prNumber/reviews") {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(params)
            }
        }
        requireSuccess(response, "POST /repos/$owner/$repo/pulls/$prNumber/reviews (comments)")
    }

    private fun JsonObjectBuilder.putReviewComment(comment: InlineCommentData) {
        put("path", comment.filePath)
        put("body", comment.body)
        put("line", comment.line)
        put("side", RIGHT_SIDE)
        val startLine = comment.startLine
        if (startLine != null && startLine < comment.line) {
            put("start_line", startLine)
            put("start_side", RIGHT_SIDE)
        }
    }

    protected companion object {
        const val RIGHT_SIDE = "RIGHT"
        const val PLATFORM_NAME = "GitHub"
        const val REVIEW_STATE_APPROVED = "APPROVED"
    }
}
