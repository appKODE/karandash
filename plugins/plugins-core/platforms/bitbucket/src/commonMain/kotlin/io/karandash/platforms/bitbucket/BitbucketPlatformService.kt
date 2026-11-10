@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.bitbucket

import io.karandash.api.PluginContext
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.MergeRequestField
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
import io.karandash.models.CiJobStatus
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.pagination.PaginationProbe
import io.karandash.providers.common.AdapterHelpers
import io.karandash.providers.common.ForgeCiEnvironment
import io.karandash.providers.common.GitRemoteUrlParser
import io.karandash.providers.common.ProjectIdParser
import io.karandash.providers.common.SupersededInlineComment
import io.karandash.providers.common.UnifiedDiff
import io.karandash.providers.common.initializeWithCredentials
import io.karandash.providers.common.requireMrIdAsInt
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.platform.ReinitializableClient
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Unified Bitbucket platform service module.
 *
 * Provides merge request fetching, comment posting, and mutation capabilities
 * for Bitbucket Cloud.
 * @param engine HTTP engine of every client this service builds, for tests (a MockEngine, also from other modules);
 *   production leaves it null, the platform engine.
 */
class BitbucketPlatformService(
    private val engine: HttpClientEngine? = null,
) : AbstractTypedPlatformServiceModule<BitbucketConfig>(BitbucketConfig.serializer()),
    ResultStatusPublisher {
    companion object {
        const val ID = "bitbucket"
        private const val PLATFORM_NAME = "Bitbucket"
        private const val DEFAULT_API_URL = BitbucketConfig.DEFAULT_URL
        private const val PROJECT_ID_FORMAT = "workspace/repo-slug"
        private const val MR_ID_LABEL = "PR ID"
        private val URL_REGEX = Regex("""https://bitbucket\.org/([^/]+)/([^/]+)/pull-requests/(\d+)""")
        private val PARSEMR_REGEX = Regex("""https?://bitbucket\.org/([^/]+)/([^/]+)/pull-requests/(\d+)""")
        private val SSH_REGEX = Regex("""git@bitbucket\.org:([^/]+/[^/]+?)(?:\.git)?$""")
        private val HTTPS_REGEX = Regex("""https?://bitbucket\.org/([^/]+/[^/]+?)(?:\.git)?$""")
    }

    override val id: String = ID
    override val name: String = "Bitbucket"
    override val version: String = "1.7.0"
    override val defaultPriority: Int = 30
    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    override val description: String = "Bitbucket Cloud pull request provider."

    override suspend fun defaultApiUrl(): String = DEFAULT_API_URL

    override suspend fun authTokenHint(): String = "BITBUCKET_TOKEN"

    override fun permissionScope(): String =
        "Required: repository read permission." +
            "\nFor app passwords, ensure 'Repositories: Read' and 'Pull requests: Read' are enabled."

    override fun tokenLocationHint(): String = "Verify your app password at: Personal settings > App passwords."

    override fun notFoundGuidance(): String =
        "Bitbucket returns 404 for private repositories your credentials cannot read." +
            "\nVerify the workspace and repository slug, and that the credentials have repository read access."

    internal val client = ReinitializableClient<BitbucketClient> { it.close() }
    private var logger: Logger = NoOpLogger

    override suspend fun initializeTyped(
        context: PluginContext,
        config: BitbucketConfig
    ): Boolean {
        logger = context.logger
        return initializeWithCredentials(
            context = context,
            platformName = PLATFORM_NAME,
            moduleType = "platform service",
            resolveCredentials = { BitbucketCredentials.resolve(context.pluginConfig, context::env) },
            createClient = { creds ->
                client.rebuild(listOf(creds, context.allowLocalhost)) {
                    BitbucketClient(
                        token = creds.token,
                        allowLocalhost = context.allowLocalhost,
                        logger = context.logger,
                        baseUrl = creds.baseUrl,
                        username = creds.username,
                        engine = engine,
                    )
                }
                setManagedCloseable(PlatformCloseable { client.close() })
            },
        )
    }

    // ── Provider methods ──

    /** Bitbucket Cloud's pull request API reports neither merge conflicts nor labels. */
    override fun unavailableMergeRequestFields(): Set<MergeRequestField> = setOf(MergeRequestField.CONFLICTS, MergeRequestField.LABELS)

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest {
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return fetchPullRequest(workspace, repoSlug, mrId)
    }

    override suspend fun currentUsername(): String? = withInitializedClient(client.value, PLATFORM_NAME) { it.fetchCurrentUser().handle() }

    /** Membership of the repository's workspace (`GET /workspaces/{workspace}/members`), matched by account name. */
    override suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean {
        val (workspace, _) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return withInitializedClient(client.value, PLATFORM_NAME) { c -> c.listWorkspaceMembers(workspace).any { it.handle() == username } }
    }

    override suspend fun fetchApprovalSignals(
        projectId: String,
        mrId: String,
    ): List<ApprovalSignal> {
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        return withInitializedClient(client.value, PLATFORM_NAME) { approvalSignals(it.listComments(workspace, repoSlug, prId)) }
    }

    override suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit> {
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return withInitializedClient(client.value, PLATFORM_NAME) { c ->
            c.searchPullRequests(workspace, repoSlug, searchQuery(text, author), limit).map { it.toSearchHit() }
        }
    }

    override suspend fun checkAuth(): Boolean? = withInitializedClient(client.value, PLATFORM_NAME) { it.checkAuth("${it.baseUrl}/user") }

    override suspend fun rateLimitStatus(): RateLimitInfo? = client.value?.lastRateLimit()

    override suspend fun listOpenMergeRequestIds(
        projectId: String,
        targetBranch: String,
        limit: Int,
    ): List<String> {
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return withInitializedClient(client.value, PLATFORM_NAME) { it.listOpenPullRequestIds(workspace, repoSlug, targetBranch, limit) }
    }

    override val supportsFileContent: Boolean = true

    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? {
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        return withInitializedClient(client.value, PLATFORM_NAME) { it.fetchFileContent(workspace, repoSlug, ref, path) }
    }

    override suspend fun supports(operation: PlatformOperation): Boolean = operation in SUPPORTED

    /** A Code Insights report with annotations on the head commit. */
    override suspend fun publishResultStatus(
        projectId: String,
        mrId: String,
        status: ResultStatus,
    ) {
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        withInitializedClient(client.value, PLATFORM_NAME) { it.publishCodeInsights(workspace, repoSlug, status) }
    }

    override suspend fun mergeMr(
        projectId: String,
        mrId: String,
        options: MergeOptions,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
                val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
                val body = buildJsonObject {
                    put("merge_strategy", if (options.squash) "squash" else "merge_commit")
                    put("close_source_branch", options.deleteSourceBranch)
                    options.commitMessage?.let { put("message", it) }
                }
                bitbucketClient.mergePullRequest(workspace, repoSlug, requireMrIdAsInt(mrId, MR_ID_LABEL), body)
            }
        }

    override suspend fun closeMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
                val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
                bitbucketClient.declinePullRequest(workspace, repoSlug, requireMrIdAsInt(mrId, MR_ID_LABEL))
            }
        }

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest {
        val match = URL_REGEX.find(url)
            ?: throw IllegalArgumentException("Invalid Bitbucket PR URL: $url")
        val (workspace, repoSlug, prId) = match.destructured
        return fetchPullRequest(workspace, repoSlug, prId)
    }

    override suspend fun fetchFileBlame(
        projectId: String,
        filePath: String,
        ref: String
    ): List<BlameEntry> {
        val activeClient = client.value ?: return emptyList()
        return bestEffort("Bitbucket fetchFileBlame", emptyList()) {
            val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            val historyEntries = activeClient.fetchFileHistory(workspace, repoSlug, ref, filePath)
            val commits = historyEntries.map { entry ->
                BitbucketAdapter.toCommit(entry.commit)
                    ?: activeClient.fetchCommitDetail(workspace, repoSlug, entry.commit.hash)
            }
            BitbucketAdapter.toBlameEntries(commits)
        }
    }

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? {
        if (env("BITBUCKET_BUILD_NUMBER") == null) {
            return ForgeCiEnvironment.woodpecker(env, id, setOf("bitbucket")) { DEFAULT_API_URL }
                ?: ForgeCiEnvironment.codeBuild(env, id, DEFAULT_API_URL, ::parseGitRemoteUrl)
        }
        val projectId = env("BITBUCKET_REPO_FULL_NAME")
        val mrId = env("BITBUCKET_PR_ID")
        val token = env("BITBUCKET_ACCESS_TOKEN") ?: env("BITBUCKET_TOKEN")
        return CIEnvironment(platform = id, projectId = projectId, mrId = mrId, token = token, baseUrl = DEFAULT_API_URL)
    }

    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? {
        val match = PARSEMR_REGEX.find(url) ?: return null
        val workspace = match.groupValues[1]
        val repo = match.groupValues[2]
        val prId = match.groupValues[3]
        return ParsedMergeRequestUrl(platform = id, baseUrl = DEFAULT_API_URL, projectId = "$workspace/$repo", mrId = prId)
    }

    override suspend fun parseGitRemoteUrl(url: String): String? = GitRemoteUrlParser.parse(url, SSH_REGEX, HTTPS_REGEX)

    // ── Comment methods ──

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String
    ): String =
        withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
            val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            val comment = bitbucketClient.postComment(workspace, repoSlug, requireMrIdAsInt(mrId), body)
            comment.id.toString()
        }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        bitbucketClient.updateComment(workspace, repoSlug, requireMrIdAsInt(mrId), commentId.toLong(), body)
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
        withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
            val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            val comments = bitbucketClient.listComments(workspace, repoSlug, requireMrIdAsInt(mrId))
            comments.filter { comment -> AdapterHelpers.isSummaryComment(comment.content?.raw, marker) }.map { it.id.toString() }
        }

    override suspend fun listMarkedComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
            val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            // Comments come oldest first.
            bitbucketClient
                .listComments(workspace, repoSlug, requireMrIdAsInt(mrId))
                .mapNotNull { it.content?.raw }
                .filter { marker in it }
                .reversed()
        }

    override suspend fun deleteComment(
        projectId: String,
        mrId: String,
        commentId: String,
    ) = withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        bitbucketClient.deleteComment(workspace, repoSlug, requireMrIdAsInt(mrId), commentId.toLong())
    }

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String =
        withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
            val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            val comment = bitbucketClient.createInlineComment(
                workspace = workspace,
                repoSlug = repoSlug,
                prId = requireMrIdAsInt(mrId),
                body = body,
                filePath = filePath,
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
    ): Int = supersedeMarkedInlineComments(projectId, mrId, marker, filePaths)

    /** Resolves the thread of comment [threadId], replying [note] first when given. */
    override suspend fun resolveThread(
        projectId: String,
        mrId: String,
        threadId: String,
        note: String?,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
                val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
                val id = requireNotNull(threadId.toLongOrNull()) { "Bitbucket comment id must be a number: $threadId" }
                note?.let { bitbucketClient.replyToComment(workspace, repoSlug, requireMrIdAsInt(mrId), id, it) }
                bitbucketClient.resolveComment(workspace, repoSlug, requireMrIdAsInt(mrId), id)
            }
        }

    /** Marks earlier inline comments outdated in place and resolves their threads; never deletes them. */
    private suspend fun supersedeMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int =
        withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
            val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
            val inline = bitbucketClient.listComments(workspace, repoSlug, requireMrIdAsInt(mrId)).filter { it.inline != null }
            SupersededInlineComment.supersede(inline, marker, filePaths, logger, { it.content?.raw }, { it.inline?.path }) { comment ->
                val body = SupersededInlineComment.body(comment.content?.raw.orEmpty())
                bitbucketClient.updateComment(workspace, repoSlug, requireMrIdAsInt(mrId), comment.id, body)
                bitbucketClient.resolveComment(workspace, repoSlug, requireMrIdAsInt(mrId), comment.id)
            }
        }

    // ── Mutator methods ──

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ): Unit = throw UnsupportedPlatformOperationException("addLabels")

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ): Unit = throw UnsupportedPlatformOperationException("removeLabels")

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean
    ) = withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        val currentPr = bitbucketClient.fetchPullRequest(workspace, repoSlug, prId)
        val newTitle = if (draft) {
            if (!currentPr.title.startsWith("Draft: ") && !currentPr.title.startsWith("WIP: ")) {
                "Draft: ${currentPr.title}"
            } else {
                currentPr.title
            }
        } else {
            currentPr.title.removePrefix("Draft: ").removePrefix("WIP: ")
        }
        bitbucketClient.updatePullRequest(workspace, repoSlug, prId, mapOf("title" to newTitle))
    }

    override suspend fun addReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = changeReviewers(projectId, mrId, usernames, add = true)

    override suspend fun removeReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = changeReviewers(projectId, mrId, usernames, add = false)

    private suspend fun changeReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
        add: Boolean,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
                val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
                bitbucketClient.updateReviewers(workspace, repoSlug, requireMrIdAsInt(mrId, MR_ID_LABEL), usernames, add)
            }
        }

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        bitbucketClient.updatePullRequest(workspace, repoSlug, prId, mapOf("title" to title))
    }

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        bitbucketClient.updatePullRequest(workspace, repoSlug, prId, mapOf("description" to description))
    }

    override suspend fun approve(
        projectId: String,
        mrId: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        if (bitbucketClient.isApprovedByCurrentUser(workspace, repoSlug, prId)) {
            logger.debug("Skipping approve: already approved by current user")
            return@withInitializedClient
        }
        bitbucketClient.approvePullRequest(workspace, repoSlug, prId)
    }

    override suspend fun unapprove(
        projectId: String,
        mrId: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { bitbucketClient ->
        val (workspace, repoSlug) = ProjectIdParser.parsePair(projectId, PROJECT_ID_FORMAT)
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        if (!bitbucketClient.isApprovedByCurrentUser(workspace, repoSlug, prId)) {
            logger.debug("Skipping unapprove: not currently approved by current user")
            return@withInitializedClient
        }
        bitbucketClient.unapprovePullRequest(workspace, repoSlug, prId)
    }

    // ── Private helpers ──

    private suspend fun fetchPullRequest(
        workspace: String,
        repoSlug: String,
        prId: String
    ): MergeRequest {
        val activeClient = client.value ?: error("Bitbucket not initialized")
        return coroutineScope {
            val prDeferred = async { activeClient.fetchPullRequest(workspace, repoSlug, prId) }
            val commitsDeferred = async { PaginationProbe.probe { activeClient.fetchCommits(workspace, repoSlug, prId) } }
            val diffStatsDeferred = async { activeClient.fetchDiffStats(workspace, repoSlug, prId) }
            val diffDeferred = async { activeClient.fetchDiff(workspace, repoSlug, prId) }
            val ciJobsDeferred = async { fetchCiJobs(activeClient, workspace, repoSlug, prId) }
            val mr = BitbucketAdapter.toMergeRequest(prDeferred.await(), commitsDeferred.await().first, diffStatsDeferred.await())
            val rawDiff = diffDeferred.await()
            if (rawDiff == null) {
                logger.warn("Bitbucket: could not download the diff of $workspace/$repoSlug#$prId; changes carry no patches")
            }
            val diffs = rawDiff?.let(UnifiedDiff::splitByFile).orEmpty()
            val ciJobs = ciJobsDeferred.await()
            mr.copy(
                changes = UnifiedDiff.attach(mr.changes, diffs),
                ciStatus = ciJobs?.let { AdapterHelpers.aggregateCiStatus(it) },
                ciJobs = ciJobs.orEmpty(),
                commitsTruncated = commitsDeferred.await().second,
            )
        }
    }

    /** Build statuses as CI jobs (one per status key); null with a warning when the lookup fails. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchCiJobs(
        activeClient: BitbucketClient,
        workspace: String,
        repoSlug: String,
        prId: String,
    ): List<CiJobStatus>? =
        try {
            BitbucketAdapter.toCiJobs(activeClient.fetchStatuses(workspace, repoSlug, prId))
        } catch (e: Exception) {
            // boundary: CI status is optional MR data; a failed lookup must not fail the fetch
            e.rethrowCancellation()
            logger.warn("Bitbucket: could not fetch build statuses for $workspace/$repoSlug#$prId: ${e.message}")
            null
        }
}

/** The operations this platform implements; everything else is [PlatformOperation] unsupported. */
private val SUPPORTED: Set<PlatformOperation> = setOf(
    PlatformOperation.DRAFT,
    PlatformOperation.APPROVE,
    PlatformOperation.COMMENTS,
    PlatformOperation.MERGE,
    PlatformOperation.POST_INLINE_COMMENTS,
    PlatformOperation.LIST_OPEN,
    PlatformOperation.FILE_CONTENT,
    PlatformOperation.RESOLVE_DISCUSSIONS,
    PlatformOperation.INLINE_RANGES,
    PlatformOperation.MANAGE_REVIEWERS,
    PlatformOperation.RESULT_STATUS,
    PlatformOperation.APPROVAL_SIGNALS,
    PlatformOperation.SEARCH,
)

/** LSP hover text for this module's config block. */
private val HOVER_DOCUMENTATION = """
    # bitbucket

    **Bitbucket Provider**

    Fetches pull request data from Bitbucket Cloud or Server.

    ## Properties
    - `url: String` - Bitbucket instance URL
    - `username: String` - Bitbucket username
    - `password: String` - App password or token
""".trimIndent()
