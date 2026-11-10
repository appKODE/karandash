package io.karandash.platforms.gerrit

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.cache.CachedValue
import io.karandash.http.HttpAuth
import io.karandash.http.PathSegmentEncoder
import io.karandash.providers.common.BaseProviderClient
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * HTTP client for Gerrit API
 *
 * Supports credential resolution from config or environment variables:
 * - Username: config.username > GERRIT_USERNAME
 * - Token (HTTP password): config.token > GERRIT_TOKEN
 * - URL: config.url > GERRIT_URL (required, no default)
 *
 * Gerrit uses HTTP Basic Authentication (`username:http-password`) under the `/a/` path prefix; without both
 * username and token, calls are anonymous and unprefixed.
 */
class GerritClient(
    baseUrl: String,
    private val token: String? = null,
    httpClient: HttpClient? = null,
    allowLocalhost: Boolean = false,
    logger: Logger = NoOpLogger,
    private val username: String? = null,
    private val notify: GerritNotify = GerritNotify.OWNER,
    engine: HttpClientEngine? = null,
) : BaseProviderClient(
        baseUrl = baseUrl,
        httpClient = httpClient,
        allowLocalhost = allowLocalhost,
        logger = logger,
        engine = engine,
    ) {
    override val platformName: String = "Gerrit"

    private val cachedAccount = MutableStateFlow<CachedValue<GerritAccountInfo?>>(CachedValue.Unresolved)

    /**
     * Check if authentication token is configured
     */
    fun hasToken(): Boolean = token != null

    /**
     * Configure Gerrit authentication headers for a request.
     * Gerrit uses HTTP Basic Authentication where the token serves as the password.
     */
    override fun credential(): HttpAuth? = if (username != null && hasToken()) HttpAuth.Basic(username, checkNotNull(token)) else null

    /** Authenticated Gerrit REST calls live under `/a/`; anonymous ones do not. */
    private val apiUrl: String get() = if (username != null && hasToken()) "$baseUrl/a" else baseUrl

    /** Gerrit rejects every write without username and HTTP password; fail before the request with the fix named. */
    private fun requireAuth(action: String) =
        check(username != null && hasToken()) {
            "Gerrit username and token required to $action (config 'username'/'token' or GERRIT_USERNAME/GERRIT_TOKEN)"
        }

    /**
     * Get change information
     * @param projectId The project ID (URL-encoded)
     * @param changeId The change ID (numeric or Change-Id)
     * @return GerritChangeInfo object
     */
    suspend fun getChange(
        projectId: String,
        changeId: String,
    ): GerritChangeInfo {
        val url = "$apiUrl/changes/${encodeProjectId(projectId)}~$changeId?o=CURRENT_REVISION&o=LABELS&o=MESSAGES"
        val responseBody = trackedRequest { get(url) { configureAuth() } }.bodyAsText()
        val cleanedBody = stripGerritPrefix(responseBody)
        return json.decodeFromString(cleanedBody)
    }

    /**
     * Post a review comment on a change
     * Uses Gerrit's Set Review endpoint which handles both comments and votes
     * @param changeId The change ID
     * @param message The review message/comment
     * @return GerritReviewResult with the posted review
     */
    suspend fun postReview(
        changeId: String,
        message: String,
    ): GerritReviewResult {
        requireAuth("post a review")
        val url = "$apiUrl/changes/$changeId/revisions/current/review"
        val reviewInput = GerritReviewInput(message = message, omitDuplicateComments = true, tag = TAG, notify = notify)
        val responseBody = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(reviewInput)
            }
        }.bodyAsText()
        val cleanedBody = stripGerritPrefix(responseBody)
        return json.decodeFromString(cleanedBody)
    }

    /**
     * List comments (change messages) on a change
     * @param changeId The change ID
     * @return List of GerritChangeMessageInfo objects
     */
    suspend fun listComments(changeId: String): List<GerritChangeMessageInfo> {
        val url = "$apiUrl/changes/$changeId?o=MESSAGES"
        val responseBody = trackedRequest { get(url) { configureAuth() } }.bodyAsText()
        val cleanedBody = stripGerritPrefix(responseBody)
        val changeInfo: GerritChangeInfo = json.decodeFromString(cleanedBody)
        return changeInfo.messages ?: emptyList()
    }

    /**
     * Set review votes (labels) on a change
     * @param changeId The change ID
     * @param labels Map of label names to scores (e.g., "Code-Review" to +1)
     * @return GerritReviewResult with the updated review
     */
    suspend fun setReview(
        changeId: String,
        labels: Map<String, Int>,
    ): GerritReviewResult {
        requireAuth("set review labels")
        val url = "$apiUrl/changes/$changeId/revisions/current/review"
        val reviewInput = GerritReviewInput(labels = labels, tag = TAG, notify = notify)
        val responseBody = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(reviewInput)
            }
        }.bodyAsText()
        val cleanedBody = stripGerritPrefix(responseBody)
        return json.decodeFromString(cleanedBody)
    }

    /**
     * Post inline comments on a specific revision of a change.
     * Uses the Set Review endpoint with file-level comments.
     * @param changeId The change ID
     * @param revisionId The revision SHA to comment on
     * @param comments Map of file path to list of inline comments
     * @param message Change message of the same review (the summary), so both cost one notification
     * @return GerritReviewResult
     */
    suspend fun postInlineComments(
        changeId: String,
        revisionId: String,
        comments: Map<String, List<GerritInlineComment>>,
        message: String? = null,
    ): GerritReviewResult {
        requireAuth("post inline comments")
        val url = "$apiUrl/changes/$changeId/revisions/$revisionId/review"
        val reviewInput = GerritInlineReviewInput(message, comments, omitDuplicateComments = true, tag = TAG, notify = notify)
        val responseBody = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(reviewInput)
            }
        }.bodyAsText()
        val cleanedBody = stripGerritPrefix(responseBody)
        return json.decodeFromString(cleanedBody)
    }

    /**
     * Set hashtags on a change
     * @param changeId The change ID
     * @param add Hashtags to add
     * @param remove Hashtags to remove
     */
    suspend fun setHashtags(
        changeId: String,
        add: List<String>,
        remove: List<String>,
    ) {
        requireAuth("set hashtags")
        val url = "$apiUrl/changes/$changeId/hashtags"
        val input = GerritHashtagsInput(add = add, remove = remove)
        trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(input)
            }
        }
    }

    /** Published inline comments on a change, across all files. */
    suspend fun listInlineComments(changeId: String): List<GerritPublishedComment> {
        val url = "$apiUrl/changes/$changeId/comments"
        val response = requireSuccess(trackedRequest { get(url) { configureAuth() } }, "GET $url")
        val byFile: Map<String, List<GerritPublishedComment>> = json.decodeFromString(stripGerritPrefix(response.bodyAsText()))
        return byFile.flatMap { (path, comments) -> comments.map { it.copy(path = path) } }
    }

    /** POSTs a change action such as `submit`, `abandon` or `restore`. */
    suspend fun changeAction(
        changeId: String,
        action: String,
    ) {
        requireAuth("change the change state")
        val url = "$apiUrl/changes/$changeId/$action"
        requireSuccess(trackedRequest { post(url) { configureAuth() } }, "POST $url")
    }

    /** `POST /changes/{id}/reviewers`: [reviewer] is an account (username, email or id) or a group. */
    suspend fun addReviewer(
        changeId: String,
        reviewer: String,
    ) {
        requireAuth("add a reviewer")
        val response = trackedRequest {
            post("$apiUrl/changes/$changeId/reviewers") {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("reviewer", reviewer) })
            }
        }
        requireSuccess(response, "POST /changes/$changeId/reviewers")
    }

    /** `DELETE /changes/{id}/reviewers/{account}`. */
    suspend fun deleteReviewer(
        changeId: String,
        account: String,
    ) {
        requireAuth("remove a reviewer")
        val url = "$apiUrl/changes/$changeId/reviewers/${PathSegmentEncoder.encode(account)}"
        requireSuccess(trackedRequest { delete(url) { configureAuth() } }, "DELETE $url")
    }

    /** `GET /accounts/self`: true when the credentials are accepted, false on 401/403. */
    suspend fun checkAuth(): Boolean = checkAuth("$apiUrl/accounts/self")

    /**
     * Mark a change as Work-In-Progress
     * @param changeId The change ID
     */
    suspend fun setWorkInProgress(changeId: String) {
        postStateChange("$apiUrl/changes/$changeId/wip")
    }

    /**
     * Mark a change as ready for review (clear WIP status)
     * @param changeId The change ID
     */
    suspend fun setReadyForReview(changeId: String) {
        postStateChange("$apiUrl/changes/$changeId/ready")
    }

    /** The change's current commit message (`GET /changes/{id}/message`). */
    suspend fun getCommitMessage(changeId: String): String {
        val url = "$apiUrl/changes/$changeId/message"
        val responseBody = requireSuccess(trackedRequest { get(url) { configureAuth() } }, "GET $url").bodyAsText()
        return json.decodeFromString<GerritCommitMessageInfo>(stripGerritPrefix(responseBody)).fullMessage
    }

    /**
     * Update the subject (title) of a change via its commit message.
     * The new subject replaces only the first line; the body and footers (Change-Id) are kept.
     * @param changeId The change ID
     * @param subject The new subject line
     */
    suspend fun updateSubject(
        changeId: String,
        subject: String,
    ) {
        val rest = getCommitMessage(changeId).substringAfter('\n', missingDelimiterValue = "")
        putCommitMessage(changeId, if (rest.isEmpty()) subject else "$subject\n$rest")
    }

    /**
     * Update the description of a change: the commit message body between the subject line and the
     * footer block (the last paragraph, when it carries `Change-Id:`). Subject and footers are kept; the topic is not touched.
     * @param changeId The change ID
     * @param description The new commit message body
     */
    suspend fun updateDescription(
        changeId: String,
        description: String,
    ) {
        val current = getCommitMessage(changeId).trimEnd()
        val subject = current.substringBefore('\n')
        val footer = current
            .substringAfterLast("\n\n", missingDelimiterValue = "")
            .takeIf { block -> block.lines().any { it.startsWith(CHANGE_ID_FOOTER) } }
        val message = listOfNotNull(subject, description.trim().ifEmpty { null }, footer).joinToString("\n\n")
        putCommitMessage(changeId, "$message\n")
    }

    private suspend fun putCommitMessage(
        changeId: String,
        message: String,
    ) {
        requireAuth("edit the commit message")
        val url = "$apiUrl/changes/$changeId/message"
        val response = trackedRequest {
            put(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(GerritCommitMessageInput(message = message))
            }
        }
        requireSuccessOrConflict(response, "PUT $url")
    }

    private suspend fun postStateChange(url: String) {
        requireAuth("change the work-in-progress state")
        requireSuccessOrConflict(trackedRequest { post(url) { configureAuth() } }, "POST $url")
    }

    /**
     * Gerrit answers 409 when a write would not change anything: `wip` on a WIP change, `ready` on a ready one,
     * a commit message equal to the current one. Those are no-ops here; any other failure throws.
     */
    private suspend fun requireSuccessOrConflict(
        response: HttpResponse,
        requestLabel: String,
    ) {
        if (response.status != HttpStatusCode.Conflict) requireSuccess(response, requestLabel)
    }

    /**
     * Fetch the account ID of the currently authenticated user.
     * Result is cached after the first call. Returns null if the API call fails.
     */
    suspend fun fetchCurrentAccountId(): Long? = fetchCurrentAccount()?.accountId

    /** Username of the authenticated user (`GET /accounts/self`), or null when it cannot be read. */
    suspend fun fetchCurrentUsername(): String? = fetchCurrentAccount()?.username

    /** The authenticated user's account (`GET /accounts/self`), cached after the first call; null when the call fails. */
    private suspend fun fetchCurrentAccount(): GerritAccountInfo? {
        val current = cachedAccount.value
        if (current is CachedValue.Resolved) return current.value
        val resolved = try {
            val url = "$apiUrl/accounts/self"
            val responseBody = requireSuccess(trackedRequest { get(url) { configureAuth() } }, "GET /accounts/self").bodyAsText()
            json.decodeFromString<GerritAccountInfo>(stripGerritPrefix(responseBody))
        } catch (ignored: Exception) {
            // boundary: platform API call; account/vote lookup is best-effort
            ignored.rethrowCancellation()
            logger.warn("Gerrit fetchCurrentAccount failed (${ignored.message ?: ignored::class.simpleName})")
            null
        }
        cachedAccount.value = CachedValue.Resolved(resolved)
        return resolved
    }

    /**
     * Get the current user's Code-Review vote on a change.
     * Returns the vote value (e.g. +2 for approved, 0 for no vote), or null if check fails.
     */
    suspend fun getCurrentCodeReviewVote(
        projectId: String,
        changeId: String,
    ): Int? {
        val accountId = fetchCurrentAccountId() ?: return null
        return try {
            val change = getChange(projectId, changeId)
            val codeReview = change.labels?.get(CODE_REVIEW_LABEL) ?: return null
            codeReview.all?.find { it.accountId == accountId }?.value
        } catch (ignored: Exception) {
            // boundary: platform API call; account/vote lookup is best-effort
            ignored.rethrowCancellation()
            logger.warn("Gerrit getCurrentCodeReviewVote failed (${ignored.message ?: ignored::class.simpleName})")
            null
        }
    }

    /**
     * Submit a Code-Review +2 label to approve a change.
     * @param changeId The change ID
     * @return GerritReviewResult with the updated review
     */
    suspend fun approveChange(changeId: String): GerritReviewResult = setReview(changeId, mapOf(CODE_REVIEW_LABEL to 2))

    /**
     * Remove Code-Review label (set to 0) to unapprove a change.
     * @param changeId The change ID
     * @return GerritReviewResult with the updated review
     */
    suspend fun unapproveChange(changeId: String): GerritReviewResult = setReview(changeId, mapOf(CODE_REVIEW_LABEL to 0))

    /**
     * Content of [path] at [ref] (`.../commits/{sha}/files/{path}/content` for a full sha, else the branch), or null
     * when missing, binary or over the cap. Gerrit answers base64, so the encoded body may be a third larger.
     */
    suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? {
        val refPath = if (FULL_SHA.matches(
                ref
            )
        ) {
            "commits/$ref"
        } else {
            "branches/${PathSegmentEncoder.encode(ref.removePrefix("refs/heads/"))}"
        }
        val url = "$apiUrl/projects/${encodeProjectId(projectId)}/$refPath/files/${PathSegmentEncoder.encode(path)}/content"
        val cap = PlatformServiceModule.MAX_FILE_CONTENT_BYTES.toLong()
        return getFileTextOrNull(
            url,
            "content of $path at $ref",
            bodyLimit = cap * BASE64_GROWTH_NUM / BASE64_GROWTH_DEN + BASE64_GROWTH_NUM,
            decode = { body ->
                try {
                    Base64.decode(body.decodeToString().trim())
                } catch (e: IllegalArgumentException) {
                    logger.warn("Gerrit content of $path is not base64 (${e::class.simpleName}); skipping it")
                    null
                }
            },
        )
    }

    /**
     * Encode project ID for use in URLs
     * Gerrit uses "project~changeId" format in URLs, with a slash in the project name as %2F
     */
    private fun encodeProjectId(projectId: String): String = PathSegmentEncoder.encode(projectId)

    companion object {
        private const val CODE_REVIEW_LABEL = "Code-Review"

        /** Tag of every review the bot posts; `autogenerated:` hides it under Gerrit's "Only comments" filter. */
        const val TAG = "autogenerated:karandash"
        private const val CHANGE_ID_FOOTER = "Change-Id:"
        private const val BASE64_GROWTH_NUM = 4
        private const val BASE64_GROWTH_DEN = 3
        private val FULL_SHA = Regex("^[0-9a-fA-F]{40}$")
    }
}

/** Gerrit's JSON security prefix to prevent XSSI attacks. */
private const val GERRIT_JSON_PREFIX = ")]}\'"

/** Strips Gerrit's JSON security prefix `)]}'` from a response body. */
internal fun stripGerritPrefix(responseBody: String): String =
    if (responseBody.startsWith(GERRIT_JSON_PREFIX)) {
        responseBody.substring(GERRIT_JSON_PREFIX.length).trimStart()
    } else {
        responseBody
    }
