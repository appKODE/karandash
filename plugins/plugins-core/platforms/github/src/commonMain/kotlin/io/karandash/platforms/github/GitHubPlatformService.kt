@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.github

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.PluginContext
import io.karandash.api.exception.ContentResolveException
import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.ReviewThread
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.InlineCommentBatchPoster
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.api.platform.PlatformCloseable
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.RateLimitInfo
import io.karandash.api.platform.ResultStatus
import io.karandash.api.platform.ResultStatusPublisher
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.suspendCatching
import io.karandash.models.ApprovalSignal
import io.karandash.models.BlameEntry
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.providers.common.AdapterHelpers
import io.karandash.providers.common.ForgeCiEnvironment
import io.karandash.providers.common.GitRemoteUrlParser
import io.karandash.providers.common.ProjectIdParser
import io.karandash.providers.common.SupersededInlineComment
import io.karandash.providers.common.initializeWithCredentials
import io.karandash.providers.common.requireMrIdAsInt
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.platform.ReinitializableClient
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import io.ktor.client.engine.HttpClientEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Unified GitHub platform service module.
 *
 * Provides merge request fetching, comment posting, and mutation capabilities
 * for GitHub.com and GitHub Enterprise instances.
 * @param engine HTTP engine of every client this service builds, for tests (a MockEngine, also from other modules);
 *   production leaves it null, the platform engine.
 */
class GitHubPlatformService(
    private val engine: HttpClientEngine? = null,
) : AbstractTypedPlatformServiceModule<GitHubConfig>(GitHubConfig.serializer()),
    InlineCommentBatchPoster,
    ResultStatusPublisher {
    companion object {
        const val ID = "github"
        private const val PLATFORM_NAME = "Github"
        private const val DEFAULT_API_URL = "https://api.github.com"
        internal const val PROJECT_ID_FORMAT = "owner/repo"
        private const val MR_ID_LABEL = "PR number"
    }

    override val id: String = ID
    override val name: String = PLATFORM_NAME
    override val version: String = "1.7.0"
    override val defaultPriority: Int = 20
    override val dslName: String = ID
    override val description: String = "GitHub pull request provider. Supports github.com and GitHub Enterprise."
    override val hoverDocumentation: String = """
        # github

        **GitHub Provider**

        Fetches pull request data from GitHub API. Supports GitHub.com and GitHub Enterprise.

        ## Properties
        - `url` — GitHub API URL (default: `https://api.github.com`)
        - `token` — API token (`GITHUB_TOKEN`)
        - `auth` — `{ type: app, appId, installationId, privateKey }` to act as a GitHub App installation;
          `privateKey` is the app's PEM, inline, `${'$'}{ENV}` or `{ file: path }`
    """.trimIndent()

    internal val client = ReinitializableClient<GitHubClient> { it.close() }
    private var logger: Logger = NoOpLogger

    override suspend fun defaultApiUrl(): String = DEFAULT_API_URL

    override suspend fun authTokenHint(): String = "GITHUB_TOKEN"

    override fun permissionScope(): String =
        "Required: read access to the repository." +
            "\nFor fine-grained PATs, ensure 'Pull requests: Read' permission is granted."

    override fun tokenLocationHint(): String = "Verify your token at: Settings > Developer settings > Personal access tokens."

    override fun notFoundGuidance(): String =
        "GitHub returns 404 for private repos your token cannot access." +
            "\nVerify the repo exists and your token has repository read access."

    override suspend fun initializeTyped(
        context: PluginContext,
        config: GitHubConfig
    ): Boolean {
        logger = context.logger
        val urlSet = (context.pluginConfig as? JsonObject)?.containsKey("url") == true
        if (urlSet && config.baseUrl != null && config.url != config.baseUrl) {
            context.warn("GitHub: both 'url' and 'baseUrl' are set; using url=${config.url}")
        }
        return initializeWithCredentials(
            context = context,
            platformName = PLATFORM_NAME,
            moduleType = "platform service",
            resolveCredentials = {
                GitHubCredentials.resolve(context.pluginConfig, context::env, resolveAppPrivateKey(context, config))
            },
            createClient = { creds ->
                client.rebuild(listOf(creds, context.allowLocalhost)) {
                    GitHubClient(
                        creds.baseUrl,
                        creds.token,
                        allowLocalhost = context.allowLocalhost,
                        logger = context.logger,
                        app = creds.app,
                        engine = engine,
                    )
                }
                setManagedCloseable(PlatformCloseable { client.close() })
            },
        )
    }

    // ── Provider methods ──

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest {
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return fetchPullRequest(owner, repo, requireMrIdAsInt(mrId, MR_ID_LABEL))
    }

    override suspend fun listOpenMergeRequestIds(
        projectId: String,
        targetBranch: String,
        limit: Int,
    ): List<String> {
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return withInitializedClient(client.value, PLATFORM_NAME) { it.listOpenPullRequestNumbers(owner, repo, targetBranch, limit) }
    }

    override suspend fun fetchApprovalSignals(
        projectId: String,
        mrId: String,
    ): List<ApprovalSignal> {
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return withInitializedClient(client.value, PLATFORM_NAME) { it.approvalSignals(owner, repo, requireMrIdAsInt(mrId, MR_ID_LABEL)) }
    }

    override val supportsFileContent: Boolean = true

    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? {
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return withInitializedClient(client.value, PLATFORM_NAME) { it.fetchFileContent(owner, repo, ref, path) }
    }

    override suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit> {
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return withInitializedClient(client.value, PLATFORM_NAME) { it.searchPullRequests(owner, repo, text, author, limit) }
    }

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest {
        val (owner, repo, prNumber) = parseGitHubUrl(url)
        return fetchPullRequest(owner, repo, prNumber)
    }

    override suspend fun fetchFileBlame(
        projectId: String,
        filePath: String,
        ref: String
    ): List<BlameEntry> {
        val githubClient = client.value ?: return emptyList()
        return bestEffort("GitHub fetchFileBlame", emptyList()) {
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            GitHubAdapter.toBlameEntries(githubClient.fetchFileCommits(owner, repo, filePath, ref))
        }
    }

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? {
        // Gitea and Forgejo Actions also set GITHUB_ACTIONS; those builds belong to the Gitea platform.
        if (env("GITHUB_ACTIONS") != "true" || env("GITEA_ACTIONS") == "true" || env("FORGEJO_ACTIONS") == "true") {
            return ForgeCiEnvironment.woodpecker(env, id, setOf(ID)) { web ->
                if (web == "https://github.com") DEFAULT_API_URL else "$web/api/v3"
            } ?: ForgeCiEnvironment.codeBuild(env, id, DEFAULT_API_URL, ::parseGitRemoteUrl)
        }
        val projectId = env("GITHUB_REPOSITORY")
        val mrId = CIEnvironment.pullRequestNumber(env("GITHUB_REF"))
            ?: GitHubEventPayload.pullRequestNumber(env(GitHubEventPayload.ENV_VAR), logger)
        val baseUrl = env("GITHUB_API_URL") ?: DEFAULT_API_URL
        return CIEnvironment(platform = id, projectId = projectId, mrId = mrId, token = env("GITHUB_TOKEN"), baseUrl = baseUrl)
    }

    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? {
        val match = MERGE_REQUEST_URL_REGEX.find(url) ?: return null
        val host = match.groupValues[1]
        if (!host.contains("github", ignoreCase = true)) return null
        val owner = match.groupValues[2]
        val repo = match.groupValues[3]
        val prId = match.groupValues[4]
        val apiBase = if (host == "github.com") DEFAULT_API_URL else "https://$host/api/v3"
        return ParsedMergeRequestUrl(platform = id, baseUrl = apiBase, projectId = "$owner/$repo", mrId = prId)
    }

    override suspend fun parseGitRemoteUrl(url: String): String? = GitRemoteUrlParser.parse(url, SSH_REMOTE_REGEX, HTTPS_REMOTE_REGEX)

    // ── Comment methods ──

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String
    ): String =
        withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            val comment = githubClient.postIssueComment(owner, repo, requireMrIdAsInt(mrId), body)
            comment.id.toString()
        }

    /** All [comments] in one submitted review on the head commit (one notification); returns the PR number. */
    override suspend fun postInlineComments(
        projectId: String,
        mrId: String,
        comments: List<InlineCommentData>,
        diffRefs: DiffRefs,
    ): String =
        withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            githubClient.createCommentReview(owner, repo, requireMrIdAsInt(mrId, MR_ID_LABEL), diffRefs.headSha, comments)
            mrId
        }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        githubClient.updateIssueComment(owner, repo, commentId.toLong(), body)
    }

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String
    ): String? = findAllMatchingComments(projectId, mrId, marker).firstOrNull()

    override suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            githubClient
                .listIssueComments(owner, repo, requireMrIdAsInt(mrId))
                .filter {
                    AdapterHelpers.isSummaryComment(it.body, marker)
                }.map { it.id.toString() }
        }

    override suspend fun listMarkedComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            // Issue comments come oldest first.
            githubClient
                .listIssueComments(owner, repo, requireMrIdAsInt(mrId))
                .map { it.body }
                .filter { marker in it }
                .reversed()
        }

    override suspend fun deleteComment(
        projectId: String,
        mrId: String,
        commentId: String,
    ) = withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        githubClient.deleteIssueComment(owner, repo, commentId.toLong())
    }

    override suspend fun renderSuggestionBlock(suggestedCode: String): String = AdapterHelpers.renderSuggestion(suggestedCode)

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String =
        withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            val comment = githubClient.createPullRequestReviewComment(
                owner = owner,
                repo = repo,
                prNumber = requireMrIdAsInt(mrId),
                body = body,
                commitId = diffRefs.headSha,
                path = filePath,
                line = line,
                startLine = startLine,
            )
            comment.id.toString()
        }

    override suspend fun resolveMarked(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int = withInitializedClient(client.value, PLATFORM_NAME) { it.supersedeMarked(projectId, mrId, marker, filePaths, logger) }

    /** Marked review comments with a thumbs-down reaction; resolved threads need GraphQL and are not read. */
    override suspend fun listDismissedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            githubClient
                .listPullRequestReviewComments(owner, repo, requireMrIdAsInt(mrId))
                .filter { it.body.contains(marker) && (it.reactions?.thumbsDown ?: 0) > 0 }
                .map { it.body }
        }

    override suspend fun listReviewThreads(
        projectId: String,
        mrId: String,
    ): List<ReviewThread> = withThreads(projectId, mrId) { threads, owner, repo, pr -> threads.list(owner, repo, pr) }

    override suspend fun resolveThread(
        projectId: String,
        mrId: String,
        threadId: String,
        note: String?,
    ): Result<Unit> =
        suspendCatching {
            withThreads(projectId, mrId) { threads, _, _, _ ->
                threads.resolve(threadId)
                note?.let { threads.reply(threadId, it) }
            }
        }

    override suspend fun replyToThread(
        projectId: String,
        mrId: String,
        threadId: String,
        body: String,
    ): Result<Unit> = suspendCatching { withThreads(projectId, mrId) { threads, _, _, _ -> threads.reply(threadId, body) } }

    /** [noteId] is the review comment's REST id, as [listReviewThreads] reports it. */
    override suspend fun updateNote(
        projectId: String,
        mrId: String,
        threadId: String,
        noteId: String,
        body: String,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
                val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
                val id = requireNotNull(noteId.toLongOrNull()) { "GitHub review comment id must be a number: $noteId" }
                githubClient.updatePullRequestReviewComment(owner, repo, id, body)
            }
        }

    private suspend fun <R> withThreads(
        projectId: String,
        mrId: String,
        block: suspend (GitHubReviewThreads, String, String, Int) -> R,
    ): R =
        withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            block(GitHubReviewThreads(githubClient), owner, repo, requireMrIdAsInt(mrId, MR_ID_LABEL))
        }

    override suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean =
        withInitializedClient(client.value, PLATFORM_NAME) {
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            it.hasWriteAccess(owner, repo, username)
        }

    /** Login of the token's user (`GET /user`); `null` when it cannot be read, e.g. under GitHub App auth. */
    override suspend fun currentUsername(): String? = withInitializedClient(client.value, PLATFORM_NAME) { it.fetchCurrentUsername() }

    override suspend fun checkAuth(): Boolean? =
        withInitializedClient(client.value, PLATFORM_NAME) {
            // An installation token is not a user: GET /user answers 403, the installation's repositories answer 200.
            it.checkAuth(if (it.isAppAuth) "${it.baseUrl}/installation/repositories?per_page=1" else "${it.baseUrl}/user")
        }

    /** Core REST bucket; null when `/rate_limit` is unavailable (GitHub Enterprise with rate limiting off answers 404). */
    override suspend fun rateLimitStatus(): RateLimitInfo? =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
                githubClient.fetchRateLimit().let {
                    RateLimitInfo(
                        remaining = it.remaining,
                        limit = it.limit,
                        resetEpochSeconds = it.reset
                    )
                }
            }
        }.onFailure { logger.debug("GitHub: rate limit unavailable: ${it.message}") }.getOrNull()

    override suspend fun supports(operation: PlatformOperation): Boolean = operation in SUPPORTED

    /** A check run on the head commit; see [publishCheckRun] for the token it needs. */
    override suspend fun publishResultStatus(
        projectId: String,
        mrId: String,
        status: ResultStatus,
    ) = withInitializedClient(client.value, PLATFORM_NAME) { it.publishCheckRun(projectId, status) }

    override suspend fun addReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = updateReviewers(projectId, mrId, usernames, add = true)

    override suspend fun removeReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = updateReviewers(projectId, mrId, usernames, add = false)

    override suspend fun mergeMr(
        projectId: String,
        mrId: String,
        options: MergeOptions,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
                val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
                if (options.deleteSourceBranch) {
                    logger.warn(
                        "GitHub: deleteSourceBranch is ignored; enable 'Automatically delete head branches' in the repository settings"
                    )
                }
                val body = buildJsonObject {
                    put("merge_method", if (options.squash) "squash" else "merge")
                    options.commitMessage?.let { put("commit_message", it) }
                }
                githubClient.mergePullRequest(owner, repo, requireMrIdAsInt(mrId, MR_ID_LABEL), body)
            }
        }

    override suspend fun closeMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = setState(projectId, mrId, "closed")

    override suspend fun reopenMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = setState(projectId, mrId, "open")

    private suspend fun setState(
        projectId: String,
        mrId: String,
        state: String,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
                val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
                githubClient.setPullRequestState(owner, repo, requireMrIdAsInt(mrId, MR_ID_LABEL), state)
            }
        }

    // ── Mutator methods ──

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prNumber = requireMrIdAsInt(mrId, MR_ID_LABEL)
        githubClient.addLabels(owner, repo, prNumber, labels)
    }

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prNumber = requireMrIdAsInt(mrId, MR_ID_LABEL)
        for (label in labels) {
            githubClient.removeLabel(owner, repo, prNumber, label)
        }
    }

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean
    ) = withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prNumber = requireMrIdAsInt(mrId, MR_ID_LABEL)
        githubClient.setDraft(owner, repo, prNumber, draft)
    }

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prNumber = requireMrIdAsInt(mrId, MR_ID_LABEL)
        githubClient.updatePullRequest(owner, repo, prNumber, buildJsonObject { put("title", title) })
    }

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prNumber = requireMrIdAsInt(mrId, MR_ID_LABEL)
        githubClient.updatePullRequest(owner, repo, prNumber, buildJsonObject { put("body", description) })
    }

    override suspend fun approve(
        projectId: String,
        mrId: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prNumber = requireMrIdAsInt(mrId, MR_ID_LABEL)
        if (githubClient.isApprovedByCurrentUser(owner, repo, prNumber)) {
            logger.debug("Skipping approve: already approved by current user")
            return@withInitializedClient
        }
        githubClient.createApprovalReview(owner, repo, prNumber)
    }

    override suspend fun unapprove(
        projectId: String,
        mrId: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prNumber = requireMrIdAsInt(mrId, MR_ID_LABEL)
        if (!githubClient.isApprovedByCurrentUser(owner, repo, prNumber)) {
            logger.debug("Skipping unapprove: not currently approved by current user")
            return@withInitializedClient
        }
        githubClient.dismissApprovalReview(owner, repo, prNumber)
    }

    // ── Private helpers ──

    private suspend fun fetchPullRequest(
        owner: String,
        repo: String,
        prNumber: Int
    ): MergeRequest = checkNotNull(client.value) { "Github not initialized" }.fetchFullPullRequest(owner, repo, prNumber, logger)

    private suspend fun updateReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
        add: Boolean,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { githubClient ->
                val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
                val prNumber = requireMrIdAsInt(mrId, MR_ID_LABEL)
                if (add) {
                    githubClient.requestReviewers(owner, repo, prNumber, usernames)
                } else {
                    githubClient.removeRequestedReviewers(owner, repo, prNumber, usernames)
                }
            }
        }.onFailure { logger.warn("GitHub: could not ${if (add) "request" else "remove"} reviewers $usernames: ${it.message}") }
}

/** The `auth.privateKey` PEM of a GitHub App, read through the host (`{ file: }` is relative to the config). */
@OptIn(ExperimentalKarandashApi::class)
private suspend fun resolveAppPrivateKey(
    context: PluginContext,
    config: GitHubConfig,
): String? {
    val source = config.auth?.privateKey ?: return null
    return try {
        context.resolveContent(source)
    } catch (e: ContentResolveException) {
        error("GitHub App auth.privateKey could not be read: ${e.message}")
    }
}

/** The operations this platform implements; everything else is [PlatformOperation] unsupported. */
private val SUPPORTED: Set<PlatformOperation> = setOf(
    PlatformOperation.LABELS,
    PlatformOperation.DRAFT,
    PlatformOperation.APPROVE,
    PlatformOperation.COMMENTS,
    PlatformOperation.MERGE,
    PlatformOperation.REOPEN,
    PlatformOperation.POST_INLINE_COMMENTS,
    PlatformOperation.BATCH_INLINE,
    PlatformOperation.RESULT_STATUS,
    PlatformOperation.MANAGE_REVIEWERS,
    PlatformOperation.SEARCH,
    PlatformOperation.LIST_OPEN,
    PlatformOperation.APPROVAL_SIGNALS,
    PlatformOperation.DISMISSED_INLINE,
    PlatformOperation.INLINE_RANGES,
    PlatformOperation.SUGGESTIONS,
    PlatformOperation.FILE_CONTENT,
    PlatformOperation.REVIEW_THREADS,
    PlatformOperation.RESOLVE_DISCUSSIONS,
)

private val GITHUB_URL_REGEX = Regex("""(?:https?://)?github\.com/([^/]+)/([^/]+)/pull/(\d+)""")
private val MERGE_REQUEST_URL_REGEX = Regex("""https?://([^/]+)/([^/]+)/([^/]+)/pull/(\d+)""")
private val SSH_REMOTE_REGEX = Regex("""git@github[^:]*:([^/]+/[^/]+?)(?:\.git)?$""")
private val HTTPS_REMOTE_REGEX = Regex("""https?://github[^/]*/([^/]+/[^/]+?)(?:\.git)?$""")

private fun parseGitHubUrl(url: String): Triple<String, String, Int> {
    val match = GITHUB_URL_REGEX.find(url)
        ?: throw IllegalArgumentException("Invalid GitHub URL format: $url")
    val (owner, repo, prNumber) = match.destructured
    return Triple(owner, repo, prNumber.toInt())
}

/** Marks earlier review comments outdated in place over REST, never deletes them; `resolveThread` resolves one. */
private suspend fun GitHubClient.supersedeMarked(
    projectId: String,
    mrId: String,
    marker: String,
    filePaths: Set<String>?,
    logger: Logger,
): Int {
    val (owner, repo) = ProjectIdParser.parsePair(projectId, GitHubPlatformService.PROJECT_ID_FORMAT)
    val comments = listPullRequestReviewComments(owner, repo, requireMrIdAsInt(mrId))
    return SupersededInlineComment.supersede(comments, marker, filePaths, logger, { it.body }, { it.path }) {
        updatePullRequestReviewComment(owner, repo, it.id, SupersededInlineComment.body(it.body))
    }
}
