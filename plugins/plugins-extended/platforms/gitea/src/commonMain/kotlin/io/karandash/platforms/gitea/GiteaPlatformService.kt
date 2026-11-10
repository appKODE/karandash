@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gitea

import io.karandash.api.PluginContext
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
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.suspendCatching
import io.karandash.models.ApprovalSignal
import io.karandash.models.BlameEntry
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.providers.common.AdapterHelpers
import io.karandash.providers.common.ProjectIdParser
import io.karandash.providers.common.SupersededInlineComment
import io.karandash.providers.common.initializeWithCredentials
import io.karandash.providers.common.requireMrIdAsInt
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.platform.ReinitializableClient
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Unified Gitea platform service module.
 *
 * Supports Gitea pull request fetching, comment posting, and mutation capabilities.
 * Self-hosted only.
 * @param engine HTTP engine of every client this service builds, for tests (a MockEngine, also from other modules);
 *   production leaves it null, the platform engine.
 */
class GiteaPlatformService(
    private val engine: HttpClientEngine? = null,
) : AbstractTypedPlatformServiceModule<GiteaConfig>(GiteaConfig.serializer()),
    InlineCommentBatchPoster,
    ResultStatusPublisher {
    companion object {
        const val ID = "gitea"
        private const val PLATFORM_NAME = "Gitea"
        private const val PROJECT_ID_FORMAT = "owner/repo"
        private const val MR_ID_LABEL = "pull request ID"
    }

    override val id: String = ID
    override val name: String = "Gitea"
    override val version: String = "1.7.0"
    override val defaultPriority: Int = 70
    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    override val description: String = "Gitea pull request provider. Self-hosted only."

    override suspend fun authTokenHint(): String = "GITEA_TOKEN"

    override fun permissionScope(): String = "Required: 'repo' scope on the Gitea access token."

    override fun tokenLocationHint(): String = "Verify your token at: Settings > Applications > Access Tokens."

    override fun notFoundGuidance(): String =
        "Gitea returns 404 for private repositories your token cannot access." +
            "\nVerify owner/repo and that the token has repository read access."

    private val configuredToken = MutableStateFlow<String?>(null)

    /** Owns the HTTP client (credential scoped to its host) that [commentClient] also sends through. */
    internal val providerClient = ReinitializableClient<GiteaProviderClient> { it.close() }

    /** Sends through [providerClient]'s HTTP client, so it is rebuilt with it; closing it leaves that client open. */
    internal val commentClient = ReinitializableClient<GiteaClient> { it.close() }
    private var logger: Logger = NoOpLogger

    override suspend fun initializeTyped(
        context: PluginContext,
        config: GiteaConfig
    ): Boolean {
        logger = context.logger
        return initializeWithCredentials(
            context = context,
            platformName = PLATFORM_NAME,
            moduleType = "platform service",
            credentialsOptional = true, // anonymous reads work; mutating client calls fail fast without a token
            resolveCredentials = { GiteaCredentials.resolve(context.pluginConfig, context::env) },
            createClient = { credentials ->
                val token = credentials.token ?: configuredToken.value
                configuredToken.value = token
                val provider = providerClient.rebuild(listOf(credentials.baseUrl, token, context.allowLocalhost)) {
                    GiteaProviderClient(
                        credentials.baseUrl,
                        token = token,
                        allowLocalhost = context.allowLocalhost,
                        logger = context.logger,
                        engine = engine,
                    )
                }
                commentClient.rebuild(provider) {
                    GiteaClient(
                        credentials.baseUrl,
                        token,
                        httpClient = provider.sharedHttpClient,
                        allowLocalhost = context.allowLocalhost,
                        logger = context.logger,
                    )
                }
                setManagedCloseable(
                    PlatformCloseable {
                        commentClient.close()
                        providerClient.close()
                    },
                )
            },
        )
    }

    // ── Provider methods ──

    override suspend fun listOpenMergeRequestIds(
        projectId: String,
        targetBranch: String,
        limit: Int,
    ): List<String> {
        val activeClient = providerClient.value ?: error("Gitea not initialized")
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return activeClient.listOpenPullRequestNumbers(owner, repo, targetBranch, limit)
    }

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest {
        val activeClient = providerClient.value ?: error("Gitea not initialized")
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return activeClient.fetchFullPullRequest(owner, repo, mrId, logger)
    }

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest {
        val (owner, repo, prId) = GiteaUrls.parsePullRequestUrl(url)
        return fetchMergeRequest(GiteaUrls.ownerRepoId(owner, repo), prId)
    }

    override suspend fun fetchFileBlame(
        projectId: String,
        filePath: String,
        ref: String
    ): List<BlameEntry> {
        val activeClient = providerClient.value ?: return emptyList()
        return bestEffort("Gitea fetchFileBlame", emptyList()) {
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            GiteaAdapter.toBlameEntries(activeClient.fetchFileCommits(owner, repo, filePath, ref))
        }
    }

    override val supportsFileContent: Boolean = true

    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? {
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return withInitializedClient(providerClient.value, PLATFORM_NAME) { it.fetchFileContent(owner, repo, ref, path) }
    }

    override suspend fun fetchApprovalSignals(
        projectId: String,
        mrId: String,
    ): List<ApprovalSignal> {
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prNumber = requireMrIdAsInt(mrId, MR_ID_LABEL)
        return withInitializedClient(providerClient.value, PLATFORM_NAME) {
            approvalSignals(it.listIssueComments(owner, repo, prNumber), it.listIssueReactions(owner, repo, prNumber))
        }
    }

    override suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit> {
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return withInitializedClient(providerClient.value, PLATFORM_NAME) {
            it.searchPullRequests(owner, repo, text, author, limit).map { issue -> issue.toSearchHit() }
        }
    }

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? = GiteaCredentials.detectCi(id, env)

    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? = GiteaUrls.parseMergeRequestUrl(id, url)

    override suspend fun parseGitRemoteUrl(url: String): String? = GiteaUrls.parseGitRemoteUrl(url)

    // ── Comment methods ──

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String
    ): String =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            val comment = giteaClient.postComment(owner, repo, requireMrIdAsInt(mrId), body)
            comment.id.toString()
        }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        giteaClient.updateComment(owner, repo, commentId.toLong(), body)
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
        withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            giteaClient
                .listComments(
                    owner,
                    repo,
                    requireMrIdAsInt(mrId)
                ).filter { AdapterHelpers.isSummaryComment(it.body, marker) }
                .map { it.id.toString() }
        }

    override suspend fun listMarkedComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            // Comments come oldest first.
            giteaClient
                .listComments(owner, repo, requireMrIdAsInt(mrId))
                .mapNotNull { it.body }
                .filter { marker in it }
                .reversed()
        }

    override suspend fun deleteComment(
        projectId: String,
        mrId: String,
        commentId: String,
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        giteaClient.deleteComment(owner, repo, commentId.toLong())
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
        withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            val review = giteaClient.createPullReviewComment(
                owner = owner,
                repo = repo,
                prId = requireMrIdAsInt(mrId),
                body = body,
                commitId = diffRefs.headSha,
                path = filePath,
                newPosition = line,
            )
            review.id.toString()
        }

    /** one review for the whole run, so the author gets one notification instead of one per comment. */
    override suspend fun postInlineComments(
        projectId: String,
        mrId: String,
        comments: List<InlineCommentData>,
        diffRefs: DiffRefs,
    ): String =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            giteaClient
                .createPullReview(
                    owner = owner,
                    repo = repo,
                    prId = requireMrIdAsInt(mrId),
                    commitId = diffRefs.headSha,
                    comments = comments.map { GiteaReviewCommentRequest(path = it.filePath, newPosition = it.line, body = it.body) },
                ).id
                .toString()
        }

    override suspend fun resolveMarked(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int = supersedeMarkedInlineComments(projectId, mrId, marker, filePaths)

    /**
     * Gitea 1.26+ resolves each earlier marked conversation; older servers (and Forgejo) cannot, so the comment is
     * marked outdated in place instead. Nothing is deleted either way.
     */
    private suspend fun supersedeMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int =
        withRepo(projectId) { giteaClient, owner, repo ->
            val resolve = canResolveConversations(giteaClient.serverVersion())
            val unresolved = giteaClient.listPullReviewComments(owner, repo, requireMrIdAsInt(mrId)).filter { it.resolver == null }
            SupersededInlineComment.supersede(unresolved, marker, filePaths, logger, { it.body }, { it.path }) {
                if (resolve) {
                    giteaClient.resolvePullReviewComment(owner, repo, it.id)
                } else {
                    // review comments are issue comments in Gitea, edited through the same endpoint
                    giteaClient.updateComment(owner, repo, it.id, SupersededInlineComment.body(it.body))
                }
            }
        }

    // ── Review threads ──

    override suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean = withRepo(projectId) { c, owner, repo -> c.hasWriteAccess(owner, repo, username) }

    /** Login of the token's user (`GET /user`); `null` when it cannot be read. */
    override suspend fun currentUsername(): String? =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { it.fetchCurrentUsername() }

    override suspend fun listReviewThreads(
        projectId: String,
        mrId: String,
    ): List<ReviewThread> =
        withRepo(projectId) { c, owner, repo -> giteaReviewThreads(c.listPullReviewComments(owner, repo, requireMrIdAsInt(mrId))) }

    /** Needs Gitea 1.26+; an older server (or Forgejo) fails with [UnsupportedPlatformOperationException]. */
    override suspend fun resolveThread(
        projectId: String,
        mrId: String,
        threadId: String,
        note: String?,
    ): Result<Unit> =
        suspendCatching {
            withRepo(projectId) { c, owner, repo ->
                if (!canResolveConversations(c.serverVersion())) throw UnsupportedPlatformOperationException("resolveThread")
                c.resolvePullReviewComment(owner, repo, threadId.toLong())
            }
            if (note != null) replyToThread(projectId, mrId, threadId, note).getOrThrow()
        }

    override suspend fun replyToThread(
        projectId: String,
        mrId: String,
        threadId: String,
        body: String,
    ): Result<Unit> =
        suspendCatching {
            withRepo(projectId) { c, owner, repo -> c.replyToConversation(owner, repo, requireMrIdAsInt(mrId), threadId, body) }
        }

    override suspend fun addReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> =
        suspendCatching {
            withRepo(projectId) { c, o, r -> c.requestReviewers(o, r, requireMrIdAsInt(mrId), usernames, add = true) }
        }

    override suspend fun removeReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> =
        suspendCatching {
            withRepo(projectId) { c, o, r -> c.requestReviewers(o, r, requireMrIdAsInt(mrId), usernames, add = false) }
        }

    override suspend fun updateNote(
        projectId: String,
        mrId: String,
        threadId: String,
        noteId: String,
        body: String,
    ): Result<Unit> = suspendCatching { withRepo(projectId) { c, owner, repo -> c.updateComment(owner, repo, noteId.toLong(), body) } }

    private suspend fun <T> withRepo(
        projectId: String,
        block: suspend (GiteaClient, String, String) -> T,
    ): T =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
            val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            block(giteaClient, owner, repo)
        }

    // ── Mutator methods ──

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val labelIds = resolveLabelIds(owner, repo, labels, giteaClient)
        if (labelIds.isNotEmpty()) {
            giteaClient.addLabels(owner, repo, prId, labelIds)
        }
    }

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val labelIds = resolveLabelIds(owner, repo, labels, giteaClient)
        for (labelId in labelIds) {
            giteaClient.removeLabel(owner, repo, prId, labelId)
        }
    }

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        giteaClient.editPullRequest(owner, repo, prId, buildJsonObject { put("draft", draft) })
    }

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        giteaClient.editPullRequest(owner, repo, prId, buildJsonObject { put("title", title) })
    }

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        giteaClient.editPullRequest(owner, repo, prId, buildJsonObject { put("body", description) })
    }

    override suspend fun approve(
        projectId: String,
        mrId: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        if (giteaClient.isApprovedByCurrentUser(owner, repo, prId)) {
            logger.debug("Skipping approve: already approved by current user")
            return@withInitializedClient
        }
        giteaClient.createApprovalReview(owner, repo, prId)
        Unit
    }

    override suspend fun unapprove(
        projectId: String,
        mrId: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        if (!giteaClient.isApprovedByCurrentUser(owner, repo, prId)) {
            logger.debug("Skipping unapprove: not currently approved by current user")
            return@withInitializedClient
        }
        giteaClient.dismissApprovalReview(owner, repo, prId)
        Unit
    }

    override suspend fun checkAuth(): Boolean? =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { it.checkAuth("${it.baseUrl}/api/v1/user") }

    /** [PlatformOperation.RESOLVE_DISCUSSIONS] depends on the server: Gitea 1.26+. */
    override suspend fun supports(operation: PlatformOperation): Boolean =
        operation in SUPPORTED ||
            (operation == PlatformOperation.RESOLVE_DISCUSSIONS && canResolveConversations(commentClient.value?.serverVersion()))

    /** From the `X-RateLimit-*` headers Gitea sends only when the instance has API rate limiting enabled; null otherwise. */
    override suspend fun rateLimitStatus(): RateLimitInfo? = providerClient.value?.lastRateLimit() ?: commentClient.value?.lastRateLimit()

    override suspend fun mergeMr(
        projectId: String,
        mrId: String,
        options: MergeOptions,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
                val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
                val body = buildJsonObject {
                    put("Do", if (options.squash) "squash" else "merge")
                    put("delete_branch_after_merge", options.deleteSourceBranch)
                    options.commitMessage?.let { put("MergeMessageField", it) }
                }
                giteaClient.mergePullRequest(owner, repo, requireMrIdAsInt(mrId, MR_ID_LABEL), body)
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
            withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
                val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
                giteaClient.editPullRequest(owner, repo, requireMrIdAsInt(mrId, MR_ID_LABEL), buildJsonObject { put("state", state) })
            }
        }

    override suspend fun publishResultStatus(
        projectId: String,
        mrId: String,
        status: ResultStatus,
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { giteaClient ->
        val (owner, repo) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        giteaClient.publishCommitStatus(owner, repo, status)
    }

    // ── Private helpers ──

    private suspend fun resolveLabelIds(
        owner: String,
        repo: String,
        labels: List<String>,
        giteaClient: GiteaClient,
    ): List<Long> {
        val repoLabels = giteaClient.listLabels(owner, repo)
        return labels.mapNotNull { label ->
            label.toLongOrNull()
                ?: repoLabels.find { it.name.equals(label, ignoreCase = true) }?.id
        }
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
    PlatformOperation.LIST_OPEN,
    PlatformOperation.SUGGESTIONS,
    PlatformOperation.BATCH_INLINE,
    PlatformOperation.FILE_CONTENT,
    PlatformOperation.REVIEW_THREADS,
    PlatformOperation.MANAGE_REVIEWERS,
    PlatformOperation.APPROVAL_SIGNALS,
    PlatformOperation.SEARCH,
    PlatformOperation.RESULT_STATUS,
)

/** LSP hover text for this module's config block. */
private val HOVER_DOCUMENTATION = """
    # gitea

    **Gitea Provider**

    Fetches pull request data from Gitea.

    ## Properties
    - `url: String` - Gitea instance URL
    - `token: String` - API token
""".trimIndent()
