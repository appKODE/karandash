package io.karandash.platforms.awscodecommit

import io.karandash.api.exception.ProviderApiException
import io.karandash.api.support.rethrowCancellation
import io.karandash.cache.CachedValue
import io.karandash.crypto.AwsSigV4Headers
import io.karandash.crypto.AwsSigV4Request
import io.karandash.crypto.AwsSigV4Signer
import io.karandash.http.HttpAuth
import io.karandash.models.support.KarandashJson
import io.karandash.pagination.PaginationProbe
import io.karandash.pagination.PaginationResult
import io.karandash.pagination.paginateByCursor
import io.karandash.providers.common.BaseProviderClient
import io.karandash.providers.common.UrlValidator
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.hostWithPortIfSpecified
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * HTTP client for interacting with AWS CodeCommit API
 *
 *   (`allowPrivateNetwork = true` at construction) runs in its ssrfRedirectValidationPlugin, so an arbitrary client bypasses SSRF.
 */
class AwsCodeSuiteClient(
    private val region: String = "us-east-1",
    accessKeyId: String? = null,
    secretAccessKey: String? = null,
    sessionToken: String? = null,
    httpClient: HttpClient? = null,
    allowLocalhost: Boolean = false,
    logger: Logger = NoOpLogger,
    endpoint: String? = null,
    stsEndpoint: String? = null,
    engine: HttpClientEngine? = null,
) : BaseProviderClient(
        baseUrl = endpoint ?: "https://codecommit.$region.amazonaws.com",
        httpClient = httpClient,
        allowLocalhost = allowLocalhost,
        logger = logger,
        engine = engine,
        // STS GetCallerIdentity is SigV4-signed here; an unkeyed host would have its Authorization stripped
        additionalCredentialHosts = setOf(stsEndpoint ?: "https://sts.$region.amazonaws.com"),
    ) {
    init {
        require(region.matches(Regex("^[a-z]{2}(-[a-z]+)+-\\d+$"))) {
            "Invalid AWS region format: $region. Expected format like 'us-east-1'"
        }

        val hasKey = !accessKeyId.isNullOrBlank()
        val hasSecret = !secretAccessKey.isNullOrBlank()
        require(hasKey == hasSecret) {
            "AWS explicit credentials must include both accessKeyId and secretAccessKey"
        }
    }

    override val platformName: String = "AwsCodeCommit"

    private val endpointUrl = Url("$baseUrl/")

    // private hosts are checked per request against the run's NetworkPolicy (ssrfRedirectValidationPlugin)
    private val stsUrl = Url(
        UrlValidator.validateBaseUrl(
            stsEndpoint ?: "https://sts.$region.amazonaws.com",
            allowLocalhost = allowLocalhost,
            allowPrivateNetwork = true,
        ) + "/",
    )

    // AWS SigV4 auth is computed per-request based on the payload; configureAuth() is a no-op.
    override fun credential(): HttpAuth? = null

    // AWS CodeCommit requires encodeDefaults = true for correct payload serialization; responses are decoded
    // leniently (inherited `json`) because CodeCommit returns fields the models do not declare.
    private val awsJson = KarandashJson.Strict

    private val cachedCallerArn = MutableStateFlow<CachedValue<String?>>(CachedValue.Unresolved)

    private val explicitCredentials = AwsCredentials.explicit(accessKeyId, secretAccessKey, sessionToken)

    /** Fetch a pull request by pull request ID. */
    suspend fun fetchPullRequest(pullRequestId: String): AwsPullRequest =
        postAws<AwsPullRequestResponse>(
            target = "CodeCommit_20150413.GetPullRequest",
            payload = buildJsonObject { put(FIELD_PULL_REQUEST_ID, pullRequestId) },
        ).pullRequest

    /** Ids of the pull requests in [repositoryName] with [status], every status when null (`ListPullRequests`; no destination filter). */
    suspend fun listPullRequestIds(
        repositoryName: String,
        status: String? = "OPEN",
    ): List<String> =
        collectTokenPages("ListPullRequests") { token ->
            val payload = buildJsonObject {
                put(FIELD_REPOSITORY_NAME, repositoryName)
                status?.let { put("pullRequestStatus", it) }
                token?.let { put("nextToken", it) }
            }
            val page = postAws<AwsPullRequestIdsResponse>(target = "CodeCommit_20150413.ListPullRequests", payload = payload)
            PaginationResult(page.pullRequestIds, page.nextToken)
        }

    /** The pull request's current revision ID; approval state changes must name it. */
    suspend fun currentRevisionId(pullRequestId: String): String =
        requireNotNull(fetchPullRequest(pullRequestId).revisionId) {
            "AWS CodeCommit pull request $pullRequestId has no revisionId"
        }

    /** Fetch approvals for a pull request */
    suspend fun fetchApprovals(
        pullRequestId: String,
        revisionId: String,
    ): List<AwsApproval> =
        postAws<AwsApprovalsResponse>(
            target = "CodeCommit_20150413.GetPullRequestApprovalStates",
            payload = buildJsonObject {
                put(FIELD_PULL_REQUEST_ID, pullRequestId)
                put(FIELD_REVISION_ID, revisionId)
            },
        ).approvals

    /**
     * The pull request's commits, newest first: the first-parent chain from [afterCommitId] back to
     * [beforeCommitId] (exclusive), at most [MAX_PULL_REQUEST_COMMITS] (more marks the [PaginationProbe]; no cap with
     * `http.pagination.maxPages: 0`, one GetCommit per commit). CodeCommit
     * has no operation that lists a pull request's commits, so this walks GetCommit.
     * limit: first-parent walk; a source branch that merged the destination in walks past the merge base up to
     * the cap. Walk all parents against the destination's ancestry if such branches matter.
     */
    suspend fun fetchCommitsBetween(
        repositoryName: String,
        beforeCommitId: String,
        afterCommitId: String,
    ): List<AwsCommit> {
        val commits = mutableListOf<AwsCommit>()
        val cap = if (maxPages() == Int.MAX_VALUE) Int.MAX_VALUE else MAX_PULL_REQUEST_COMMITS
        var next: String? = afterCommitId
        while (next != null && next != beforeCommitId && commits.size < cap) {
            next = fetchCommit(repositoryName, next).also { commits += it }.parents.firstOrNull()
        }
        if (next != null && next != beforeCommitId) {
            PaginationProbe.markTruncated()
            logger.warn("AWS CodeCommit: pull request has more than $cap commits; older ones are missing")
        }
        return commits
    }

    /** A blob's content as text, or null when CodeCommit does not return it (missing blob, over the size limit). */
    suspend fun fetchBlob(
        repositoryName: String,
        blobId: String,
    ): String? =
        try {
            val blob = postAws<AwsBlobResponse>(
                target = "CodeCommit_20150413.GetBlob",
                payload = buildJsonObject {
                    put(FIELD_REPOSITORY_NAME, repositoryName)
                    put("blobId", blobId)
                },
            )
            Base64.decode(blob.content).decodeToString()
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException: a body without blob content.
            logger.warn("AWS CodeCommit: no content for blob $blobId (${e::class.simpleName}); its file diff is unavailable")
            null
        } catch (e: ProviderApiException) {
            logger.warn("AWS CodeCommit: blob $blobId failed (HTTP ${e.statusCode}); its file diff is unavailable")
            null
        }

    /** Content of [path] at [ref] (`GetFile`), or null when missing, binary or over the cap. */
    suspend fun fetchFileContent(
        repositoryName: String,
        ref: String,
        path: String,
    ): String? {
        val label = "content of $path at $ref"
        val bytes = try {
            val file = postAws<AwsFileResponse>(
                target = "CodeCommit_20150413.GetFile",
                payload = buildJsonObject {
                    put(FIELD_REPOSITORY_NAME, repositoryName)
                    put("commitSpecifier", ref)
                    put("filePath", path)
                },
            )
            Base64.decode(file.fileContent)
        } catch (e: ProviderApiException) {
            if ("DoesNotExist" !in e.responseBody) logger.warn("AWS CodeCommit: $label failed (HTTP ${e.statusCode})")
            return null
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException: a body without base64 file content.
            logger.warn("AWS CodeCommit: no content for $label (${e::class.simpleName})")
            return null
        }
        return fileText(bytes, label)
    }

    /** Fetch commit details by commit ID */
    suspend fun fetchCommit(
        repositoryName: String,
        commitId: String,
    ): AwsCommit =
        postAws<AwsCommitDetailsResponse>(
            target = "CodeCommit_20150413.GetCommit",
            payload = buildJsonObject {
                put(FIELD_REPOSITORY_NAME, repositoryName)
                put("commitId", commitId)
            },
        ).commit

    /** Fetch differences (file changes) between commits */
    suspend fun fetchDifferences(
        repositoryName: String,
        beforeCommitId: String,
        afterCommitId: String,
    ): List<AwsDifference> =
        collectTokenPages("GetDifferences") { token ->
            val payload = buildJsonObject {
                put(FIELD_REPOSITORY_NAME, repositoryName)
                put("beforeCommitSpecifier", beforeCommitId)
                put("afterCommitSpecifier", afterCommitId)
                token?.let { put("NextToken", it) }
            }
            val page = postAws<AwsDifferencesResponse>(target = "CodeCommit_20150413.GetDifferences", payload = payload)
            PaginationResult(page.differences, page.nextToken)
        }

    /** Post a comment on a pull request */
    suspend fun postCommentForPullRequest(
        repoName: String,
        prId: String,
        content: String,
        beforeCommitId: String,
        afterCommitId: String,
    ): AwsComment =
        postAws<AwsCommentResponse>(
            target = "CodeCommit_20150413.PostCommentForPullRequest",
            payload = buildJsonObject {
                put(FIELD_PULL_REQUEST_ID, prId)
                put(FIELD_REPOSITORY_NAME, repoName)
                put("beforeCommitId", beforeCommitId)
                put("afterCommitId", afterCommitId)
                put(FIELD_CONTENT, content)
            },
        ).comment

    /** Get all comments for a pull request, following pagination via nextToken. */
    suspend fun getCommentsForPullRequest(
        repoName: String,
        prId: String,
    ): List<AwsComment> =
        collectTokenPages("GetCommentsForPullRequest") { token ->
            val payload = buildJsonObject {
                put(FIELD_PULL_REQUEST_ID, prId)
                put(FIELD_REPOSITORY_NAME, repoName)
                token?.let { put("nextToken", it) }
            }
            val page = postAws<AwsCommentsResponse>(target = "CodeCommit_20150413.GetCommentsForPullRequest", payload = payload)
            val comments = page.commentsForPullRequestData.flatMap { data ->
                val location = data.location
                data.comments.map {
                    it.copy(
                        filePath = location?.filePath,
                        filePosition = location?.filePosition,
                        relativeFileVersion = location?.relativeFileVersion,
                    )
                }
            }
            PaginationResult(comments, page.nextToken)
        }

    /**
     * Every page of a CodeCommit list operation, following its continuation token. Stops after [maxPages] pages
     * with a warning that later items are missing.
     */
    private suspend fun <T> collectTokenPages(
        operation: String,
        fetchPage: suspend (token: String?) -> PaginationResult<T>,
    ): List<T> = paginateByCursor(maxPages(), warnTruncated("AWS CodeCommit $operation"), fetchPage)

    /**
     * Post an inline comment on a specific file and line within a pull request.
     * @param beforeCommitId The destination (base) commit ID
     * @param afterCommitId The source (head) commit ID
     */
    suspend fun postInlineCommentForPullRequest(
        repoName: String,
        prId: String,
        content: String,
        beforeCommitId: String,
        afterCommitId: String,
        filePath: String,
        line: Int,
    ): AwsComment =
        postAws<AwsCommentResponse>(
            target = "CodeCommit_20150413.PostCommentForPullRequest",
            payload = buildJsonObject {
                put(FIELD_PULL_REQUEST_ID, prId)
                put(FIELD_REPOSITORY_NAME, repoName)
                put("beforeCommitId", beforeCommitId)
                put("afterCommitId", afterCommitId)
                put(FIELD_CONTENT, content)
                put(
                    "location",
                    buildJsonObject {
                        put("filePath", filePath)
                        put("filePosition", line.toLong())
                        put("relativeFileVersion", "AFTER")
                    },
                )
            },
        ).comment

    /** Update the content of a comment */
    suspend fun updateComment(
        commentId: String,
        content: String,
    ) {
        postAws<AwsCommentResponse>(
            target = "CodeCommit_20150413.UpdateComment",
            payload = buildJsonObject {
                put("commentId", commentId)
                put(FIELD_CONTENT, content)
            },
        )
    }

    /** `PostCommentReply`: answers comment [inReplyTo], which joins its thread. */
    suspend fun postCommentReply(
        inReplyTo: String,
        content: String,
    ): AwsComment =
        postAws<AwsCommentResponse>(
            target = "CodeCommit_20150413.PostCommentReply",
            payload = buildJsonObject {
                put("inReplyTo", inReplyTo)
                put(FIELD_CONTENT, content)
            },
        ).comment

    /** Delete the content of a comment */
    suspend fun deleteCommentContent(commentId: String) {
        postAws<JsonObject>(
            target = "CodeCommit_20150413.DeleteCommentContent",
            payload = buildJsonObject { put("commentId", commentId) },
        )
    }

    /** Update the title of a pull request */
    suspend fun updatePullRequestTitle(
        prId: String,
        title: String,
    ) {
        postAws<AwsPullRequestResponse>(
            target = "CodeCommit_20150413.UpdatePullRequestTitle",
            payload = buildJsonObject {
                put(FIELD_PULL_REQUEST_ID, prId)
                put("title", title)
            },
        )
    }

    /** Update the description of a pull request */
    suspend fun updatePullRequestDescription(
        prId: String,
        description: String,
    ) {
        postAws<AwsPullRequestResponse>(
            target = "CodeCommit_20150413.UpdatePullRequestDescription",
            payload = buildJsonObject {
                put(FIELD_PULL_REQUEST_ID, prId)
                put("description", description)
            },
        )
    }

    /**
     * STS `GetCallerIdentity`: true when AWS accepts the credentials, false when it rejects them (403),
     * null without explicit credentials (nothing to sign with). Other failures throw.
     */
    suspend fun checkCallerIdentity(): Boolean? = callerIdentity()?.status?.isSuccess()

    /**
     * The ARN of the calling identity from STS `GetCallerIdentity`, cached per client; null without explicit
     * credentials or when STS rejects them, in which case approve/unapprove are always sent.
     */
    suspend fun getCallerArn(): String? {
        val current = cachedCallerArn.value
        if (current is CachedValue.Resolved) return current.value
        val response = callerIdentity()?.takeIf { it.status.isSuccess() }
        val resolved = response
            ?.bodyAsText()
            ?.substringAfter("<Arn>", "")
            ?.substringBefore("</Arn>")
            ?.ifBlank { null }
        cachedCallerArn.value = CachedValue.Resolved(resolved)
        return resolved
    }

    /** Signed STS `GetCallerIdentity` POST; null without explicit credentials, throws on statuses other than 2xx/403. */
    private suspend fun callerIdentity(): HttpResponse? {
        val credentials = explicitCredentials ?: return null
        val signed = AwsSigV4Signer.sign(
            AwsSigV4Request(
                method = "POST",
                host = stsUrl.hostWithPortIfSpecified,
                path = stsUrl.encodedPath,
                region = region,
                service = "sts",
                headers = mapOf("content-type" to FORM_CONTENT_TYPE),
            ),
            STS_CALLER_IDENTITY_BODY.encodeToByteArray(),
            credentials,
        )
        val response = trackedRequest {
            post(stsUrl) {
                header(HttpHeaders.ContentType, FORM_CONTENT_TYPE)
                applySignature(signed)
                setBody(STS_CALLER_IDENTITY_BODY)
            }
        }
        return if (response.status.value == HTTP_FORBIDDEN) response else requireSuccess(response, "STS GetCallerIdentity")
    }

    /**
     * Merges the pull request: [squash] uses MergePullRequestBySquash, otherwise MergePullRequestByThreeWay (a merge
     * commit). CodeCommit rejects the merge unless [sourceCommitId] is still the source branch tip.
     */
    suspend fun mergePullRequest(
        pullRequestId: String,
        repositoryName: String,
        sourceCommitId: String,
        squash: Boolean,
        commitMessage: String?,
    ) {
        val operation = if (squash) "MergePullRequestBySquash" else "MergePullRequestByThreeWay"
        postAws<JsonObject>(
            target = "CodeCommit_20150413.$operation",
            payload = buildJsonObject {
                put(FIELD_PULL_REQUEST_ID, pullRequestId)
                put(FIELD_REPOSITORY_NAME, repositoryName)
                put("sourceCommitId", sourceCommitId)
                commitMessage?.let { put("commitMessage", it) }
            },
        )
    }

    /** Deletes a branch, e.g. a merged pull request's source branch. */
    suspend fun deleteBranch(
        repositoryName: String,
        branchName: String,
    ) {
        postAws<JsonObject>(
            target = "CodeCommit_20150413.DeleteBranch",
            payload = buildJsonObject {
                put(FIELD_REPOSITORY_NAME, repositoryName)
                put("branchName", branchName)
            },
        )
    }

    /** Sets the pull request status to `OPEN` or `CLOSED`. */
    suspend fun updatePullRequestStatus(
        pullRequestId: String,
        status: String,
    ) {
        postAws<JsonObject>(
            target = "CodeCommit_20150413.UpdatePullRequestStatus",
            payload = buildJsonObject {
                put(FIELD_PULL_REQUEST_ID, pullRequestId)
                put("pullRequestStatus", status)
            },
        )
    }

    /** Whether the caller already approved the pull request; null when that cannot be checked. */
    suspend fun isApprovedByCurrentCaller(
        pullRequestId: String,
        revisionId: String,
    ): Boolean? {
        val callerArn = getCallerArn() ?: return null
        return try {
            val approvals = fetchApprovals(pullRequestId, revisionId)
            approvals.any { it.userArn == callerArn && it.approvalState == "APPROVE" }
        } catch (ignored: Exception) {
            // boundary: platform API call; approval lookup is best-effort
            ignored.rethrowCancellation()
            logger.warn("AWS CodeCommit isApprovedByCurrentCaller failed (${ignored.message ?: ignored::class.simpleName})")
            null
        }
    }

    /**
     * Approve a pull request.
     * Uses the UpdatePullRequestApprovalState API action with approvalState APPROVE.
     */
    suspend fun approvePullRequest(
        pullRequestId: String,
        revisionId: String,
    ) {
        postAws<JsonObject>(
            target = "CodeCommit_20150413.UpdatePullRequestApprovalState",
            payload = buildJsonObject {
                put(FIELD_PULL_REQUEST_ID, pullRequestId)
                put(FIELD_REVISION_ID, revisionId)
                put("approvalState", "APPROVE")
            },
        )
    }

    /**
     * Revoke approval from a pull request.
     * Uses the UpdatePullRequestApprovalState API action with approvalState REVOKE.
     */
    suspend fun unapprovePullRequest(
        pullRequestId: String,
        revisionId: String,
    ) {
        postAws<JsonObject>(
            target = "CodeCommit_20150413.UpdatePullRequestApprovalState",
            payload = buildJsonObject {
                put(FIELD_PULL_REQUEST_ID, pullRequestId)
                put(FIELD_REVISION_ID, revisionId)
                put("approvalState", "REVOKE")
            },
        )
    }

    /** POSTs a signed CodeCommit JSON request; a non-2xx response throws [ProviderApiException]. */
    private suspend inline fun <reified T> postAws(
        target: String,
        payload: JsonObject,
    ): T {
        val payloadStr = awsJson.encodeToString(JsonObject.serializer(), payload)
        val signedHeaders = explicitCredentials?.let { credentials ->
            AwsSigV4Signer.sign(
                AwsSigV4Request(
                    method = "POST",
                    host = endpointUrl.hostWithPortIfSpecified,
                    path = endpointUrl.encodedPath,
                    region = region,
                    service = "codecommit",
                    headers = mapOf("content-type" to JSON_CONTENT_TYPE, "x-amz-target" to target),
                ),
                payloadStr.encodeToByteArray(),
                credentials,
            )
        }

        val response = trackedRequest {
            post(endpointUrl) {
                header("X-Amz-Target", target)
                header(HttpHeaders.ContentType, JSON_CONTENT_TYPE)
                signedHeaders?.let { applySignature(it) }
                setBody(payloadStr)
            }
        }
        return json.decodeFromString(requireSuccess(response, target).bodyAsText())
    }

    private fun HttpRequestBuilder.applySignature(signed: AwsSigV4Headers) {
        header(HttpHeaders.Authorization, signed.authorization)
        header("X-Amz-Date", signed.amzDate)
        header("X-Amz-Content-Sha256", signed.payloadSha256)
        signed.sessionToken?.let { token -> header("X-Amz-Security-Token", token) }
    }

    private companion object {
        private const val FIELD_PULL_REQUEST_ID = "pullRequestId"
        private const val FIELD_REPOSITORY_NAME = "repositoryName"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_REVISION_ID = "revisionId"
        private const val MAX_PULL_REQUEST_COMMITS = 250
        private const val HTTP_FORBIDDEN = 403
        private const val STS_CALLER_IDENTITY_BODY = "Action=GetCallerIdentity&Version=2011-06-15"
        private const val JSON_CONTENT_TYPE = "application/x-amz-json-1.1"
        private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=utf-8"
    }
}

/** Internal model for pull request response */
@Serializable
private data class AwsPullRequestResponse(
    val pullRequest: AwsPullRequest,
)

/** Internal model for comment response */
@Serializable
private data class AwsCommentResponse(
    val comment: AwsComment,
)

/** Internal model for comments list response */
@Serializable
private data class AwsCommentsResponse(
    @SerialName("commentsForPullRequestData")
    val commentsForPullRequestData: List<AwsCommentsForPullRequestData> = emptyList(),
    val nextToken: String? = null,
)

/** Internal model for comments data wrapper */
@Serializable
private data class AwsCommentsForPullRequestData(
    val comments: List<AwsComment> = emptyList(),
    /** Set for inline comments: the file and position they are attached to. */
    val location: AwsLocation? = null,
)
