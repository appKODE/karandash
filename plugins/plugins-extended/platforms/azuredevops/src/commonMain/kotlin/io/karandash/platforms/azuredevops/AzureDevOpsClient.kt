package io.karandash.platforms.azuredevops

import io.karandash.api.support.rethrowCancellation
import io.karandash.http.HttpAuth
import io.karandash.providers.common.BaseProviderClient
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HTTP client for Azure DevOps API
 */
class AzureDevOpsClient(
    baseUrl: String = AzureDevOpsPlatformService.DEFAULT_BASE_URL,
    private val token: String? = null,
    httpClient: HttpClient? = null,
    allowLocalhost: Boolean = false,
    logger: Logger = NoOpLogger,
) : BaseProviderClient(
        baseUrl = baseUrl,
        httpClient = httpClient,
        allowLocalhost = allowLocalhost,
        logger = logger,
    ) {
    override val platformName: String = "AzureDevOps"

    /**
     * Fetch a pull request from Azure DevOps
     * @param organization Azure DevOps organization
     * @param project Project name
     * @param repositoryId Repository ID
     * @param pullRequestId Pull request ID
     * @return AzurePullRequest data
     */
    suspend fun fetchPullRequest(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
    ): AzurePullRequest {
        val url = "${prUrl(organization, project, repositoryId, pullRequestId)}?api-version=7.0"
        return trackedRequest {
            get(url) {
                configureAuth()
                accept(ContentType.Application.Json)
            }
        }.body()
    }

    /**
     * Post a comment thread on a pull request
     * @param organization Azure DevOps organization
     * @param project Project name
     * @param repositoryId Repository ID
     * @param pullRequestId Pull request ID
     * @param content Comment content
     * @return Comment thread response
     */
    suspend fun postComment(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        content: String,
    ): AzureComment {
        val url = threadsUrl(organization, project, repositoryId, pullRequestId)
        val body = PostCommentRequest(
            comments = listOf(CommentContentRequest(content)),
            status = "active",
        )
        return trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }.body()
    }

    /** PATCHes the pull request with [body] (status, completion options, title, ...). */
    suspend fun updatePullRequest(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        body: JsonObject,
    ) {
        val url = "${prUrl(organization, project, repositoryId, pullRequestId)}?api-version=7.0"
        val response = trackedRequest {
            patch(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        requireSuccess(response, "PATCH $url")
    }

    /**
     * List comment threads for a pull request
     * @param organization Azure DevOps organization
     * @param project Project name
     * @param repositoryId Repository ID
     * @param pullRequestId Pull request ID
     * @return Threads response
     */
    suspend fun listThreads(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
    ): AzureThreadsResponse {
        val url = threadsUrl(organization, project, repositoryId, pullRequestId)
        return trackedRequest {
            get(url) {
                configureAuth()
                accept(ContentType.Application.Json)
            }
        }.body()
    }

    /**
     * Update a thread comment
     * @param organization Azure DevOps organization
     * @param project Project name
     * @param repositoryId Repository ID
     * @param pullRequestId Pull request ID
     * @param threadId Thread ID
     * @param commentId Comment ID
     * @param content New comment content
     */
    suspend fun updateThread(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        threadId: Int,
        commentId: Int,
        content: String,
    ) {
        val url = "${prUrl(organization, project, repositoryId, pullRequestId)}/threads/$threadId/comments/$commentId?api-version=7.0"
        val body = UpdateThreadRequest(content)
        val response = trackedRequest {
            patch(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        requireSuccess(response, "PATCH threads/$threadId/comments/$commentId")
    }

    /**
     * Create an inline comment thread on a specific file and line in a pull request diff.
     * @param organization Azure DevOps organization
     * @param project Project name
     * @param repositoryId Repository ID
     * @param pullRequestId Pull request ID
     * @param body Comment body
     * @param filePath File path relative to repository root
     * @param line Line number in the new version of the file (the last line of a range)
     * @param startLine First line of a multi-line range; null anchors the thread to [line] only
     * @return AzureComment (thread) with the created thread ID
     */
    suspend fun createInlineCommentThread(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        body: String,
        filePath: String,
        line: Int,
        startLine: Int? = null,
    ): AzureComment {
        val url = threadsUrl(organization, project, repositoryId, pullRequestId)
        val position = AzureFilePosition(line = line, offset = 1)
        val request = PostInlineCommentRequest(
            comments = listOf(InlineCommentContentRequest(content = body, commentType = 1)),
            status = "active",
            threadContext = AzureThreadContext(
                filePath = filePath,
                rightFileStart = startLine?.takeIf { it < line }?.let { AzureFilePosition(line = it, offset = 1) } ?: position,
                rightFileEnd = position,
            ),
        )
        return trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }.body()
    }

    /**
     * Add a label to a pull request
     * @param organization Azure DevOps organization
     * @param project Project name
     * @param repositoryId Repository ID
     * @param pullRequestId Pull request ID
     * @param labelName Label name
     */
    suspend fun addLabel(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        labelName: String,
    ) {
        val url = "${prUrl(organization, project, repositoryId, pullRequestId)}/labels?api-version=7.0"
        val body = AddLabelRequest(labelName)
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        requireSuccess(response, "POST labels")
    }

    /**
     * Remove a label from a pull request
     * @param organization Azure DevOps organization
     * @param project Project name
     * @param repositoryId Repository ID
     * @param pullRequestId Pull request ID
     * @param labelId Label ID
     */
    suspend fun removeLabel(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        labelId: String,
    ) {
        val url = "${prUrl(organization, project, repositoryId, pullRequestId)}/labels/$labelId?api-version=7.0"
        requireSuccess(trackedRequest { delete(url) { configureAuth() } }, "DELETE labels/$labelId")
    }

    /**
     * Get the current user's vote on a pull request.
     * Azure DevOps resolves "me" server-side to the authenticated user.
     * @return The vote value (10=approved, 0=no vote, -10=rejected), or null if the check fails.
     */
    suspend fun getCurrentUserVote(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
    ): Int? =
        try {
            val url = "${prUrl(organization, project, repositoryId, pullRequestId)}/reviewers/me?api-version=7.1"
            val response: AzureReviewerVote = trackedRequest {
                get(url) {
                    configureAuth()
                    accept(ContentType.Application.Json)
                }
            }.body()
            response.vote
        } catch (ignored: Exception) {
            // boundary: platform API call; vote lookup is best-effort
            ignored.rethrowCancellation()
            logger.warn("Azure DevOps getCurrentUserVote failed (${ignored.message ?: ignored::class.simpleName})")
            null
        }

    /**
     * Approve a pull request by submitting a vote of +10 for the given reviewer.
     * @param organization Azure DevOps organization
     * @param project Project name
     * @param repositoryId Repository ID
     * @param pullRequestId Pull request ID
     * @param reviewerId The reviewer's identity ID (the authenticated user's ID)
     */
    suspend fun approvePullRequest(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        reviewerId: String,
    ) {
        val url = reviewerUrl(organization, project, repositoryId, pullRequestId, reviewerId)
        val response = trackedRequest {
            put(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("vote", 10) })
            }
        }
        requireSuccess(response, "PUT reviewers/$reviewerId")
    }

    /**
     * Remove approval vote from a pull request by resetting the reviewer's vote to 0.
     * @param organization Azure DevOps organization
     * @param project Project name
     * @param repositoryId Repository ID
     * @param pullRequestId Pull request ID
     * @param reviewerId The reviewer's identity ID (the authenticated user's ID)
     */
    suspend fun unapprovePullRequest(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        reviewerId: String,
    ) {
        val url = reviewerUrl(organization, project, repositoryId, pullRequestId, reviewerId)
        val response = trackedRequest {
            put(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("vote", 0) })
            }
        }
        requireSuccess(response, "PUT reviewers/$reviewerId")
    }

    /** Identity ids of the pull request's reviewers (`GET reviewers`). */
    suspend fun listReviewerIds(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
    ): List<String> = getIdentities("${prUrl(organization, project, repositoryId, pullRequestId)}/reviewers?api-version=7.1")

    /** `DELETE reviewers/{reviewerId}`. */
    suspend fun removeReviewer(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        reviewerId: String,
    ) {
        val url = reviewerUrl(organization, project, repositoryId, pullRequestId, reviewerId)
        requireSuccess(trackedRequest { delete(url) { configureAuth() } }, "DELETE reviewers/$reviewerId")
    }

    /**
     * Identity ids matching [name] (an account name, email or display name) via the identities search, served by
     * `vssps.dev.azure.com` on Azure DevOps Services and by the collection itself on Azure DevOps Server.
     */
    suspend fun findIdentityIds(
        organization: String,
        name: String,
    ): List<String> {
        val host = if (baseUrl == AzureDevOpsPlatformService.DEFAULT_BASE_URL) "https://vssps.dev.azure.com" else baseUrl
        val url = URLBuilder("$host/$organization/_apis/identities")
            .apply {
                parameters.append("searchFilter", "General")
                parameters.append("filterValue", name)
                parameters.append("queryMembership", "None")
                parameters.append("api-version", "7.1")
            }.buildString()
        return getIdentities(url)
    }

    private suspend fun getIdentities(url: String): List<String> {
        val response = trackedRequest {
            get(url) {
                configureAuth()
                accept(ContentType.Application.Json)
            }
        }
        requireSuccess(response, "GET ${url.substringBefore('?')}")
        return response.body<AzureIdentityList>().value.map { it.id }
    }

    /**
     * Update the status of a comment thread (e.g., "closed", "active")
     * @param organization Azure DevOps organization
     * @param project Project name
     * @param repositoryId Repository ID
     * @param pullRequestId Pull request ID
     * @param threadId Thread ID
     * @param status New thread status
     */
    suspend fun updateThreadStatus(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        threadId: Int,
        status: String,
    ) {
        val url = "${prUrl(organization, project, repositoryId, pullRequestId)}/threads/$threadId?api-version=7.0"
        val response = trackedRequest {
            patch(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("status", status) })
            }
        }
        requireSuccess(response, "PATCH threads/$threadId")
    }

    /** Adds a reply with [content] to thread [threadId], under its first comment. */
    suspend fun replyToThread(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        threadId: Int,
        content: String,
    ) {
        val url = "${prUrl(organization, project, repositoryId, pullRequestId)}/threads/$threadId/comments?api-version=7.0"
        val response = trackedRequest {
            post(url) {
                configureAuth()
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("content", content)
                        put("parentCommentId", 1)
                        put("commentType", 1)
                    },
                )
            }
        }
        requireSuccess(response, "POST threads/$threadId/comments")
    }

    /** Deletes one comment of a pull request thread. */
    suspend fun deleteComment(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        threadId: Int,
        commentId: Int,
    ) {
        val url = "${prUrl(organization, project, repositoryId, pullRequestId)}/threads/$threadId/comments/$commentId?api-version=7.0"
        requireSuccess(trackedRequest { delete(url) { configureAuth() } }, "DELETE threads/$threadId/comments/$commentId")
    }

    // Azure DevOps uses Basic auth with the PAT as password and an empty username: "Basic base64(:token)"
    override fun credential(): HttpAuth? = token?.let { HttpAuth.Basic("", it) }

    /**
     * Response model for reviewer vote query
     */
    @Serializable
    private data class AzureReviewerVote(
        val vote: Int = 0,
    )

    private fun prUrl(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
    ): String = "$baseUrl/$organization/$project/_apis/git/repositories/$repositoryId/pullrequests/$pullRequestId"

    private fun reviewerUrl(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
        reviewerId: String,
    ): String = "${prUrl(organization, project, repositoryId, pullRequestId)}/reviewers/$reviewerId?api-version=7.1"

    private fun threadsUrl(
        organization: String,
        project: String,
        repositoryId: String,
        pullRequestId: Int,
    ): String = "${prUrl(organization, project, repositoryId, pullRequestId)}/threads?api-version=7.0"
}
