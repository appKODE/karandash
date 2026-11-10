@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gitlab

import io.karandash.api.PluginContext
import io.karandash.api.finding.FindingFeedback
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
import io.karandash.models.CiJobStatus
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.pagination.PaginationProbe
import io.karandash.providers.common.AdapterHelpers
import io.karandash.providers.common.ForgeCiEnvironment
import io.karandash.providers.common.initializeWithCredentials
import io.karandash.providers.common.requireMrIdAsInt
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.platform.ReinitializableClient
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Unified GitLab platform service module.
 *
 * Provides merge request fetching, comment posting, and mutation capabilities
 * for GitLab.com and self-hosted GitLab instances.
 * @param engine HTTP engine of every client this service builds, for tests (a MockEngine, also from other modules);
 *   production leaves it null, the platform engine.
 */
class GitLabPlatformService(
    private val engine: HttpClientEngine? = null,
) : AbstractTypedPlatformServiceModule<GitLabConfig>(GitLabConfig.serializer()),
    InlineCommentBatchPoster,
    ResultStatusPublisher {
    companion object {
        const val ID = "gitlab"
        private const val PLATFORM_NAME = "Gitlab"
        private const val DEFAULT_BASE_URL = "https://gitlab.com"
        private const val SUGGESTION_FENCE = "```suggestion\n"

        /** The title prefixes GitLab treats as draft: `Draft:`, `[Draft]`, `(Draft)` and the legacy `WIP:`. */
        private val DRAFT_PREFIX = Regex("^\\s*(?:(?:draft|wip):|\\[draft]|\\(draft\\))\\s*", RegexOption.IGNORE_CASE)

        /**
         * GitLab anchors the discussion on [line] alone, so a suggestion covering [startLine]..[line]
         * needs the `suggestion:-N+0` form to replace the lines above the anchor too.
         */
        internal fun multiLineSuggestions(
            body: String,
            line: Int,
            startLine: Int?,
        ): String {
            val above = line - (startLine ?: line)
            return if (above > 0) body.replace(SUGGESTION_FENCE, "```suggestion:-$above+0\n") else body
        }

        private val PARSED_URL_REGEX = Regex("""https?://([^/]+)/(.+?)/-/merge_requests/(\d+)""")
        private val SSH_REGEX = Regex("""git@[^:]+:(.+?)(?:\.git)?$""")
        private val HTTPS_REGEX = Regex("""https?://[^/]*gitlab[^/]*/(.+?)(?:\.git)?$""")
    }

    override val id: String = ID
    override val name: String = "GitLab"
    override val version: String = "1.7.0"
    override val defaultPriority: Int = 10
    override val supportsFileContent: Boolean = true
    override val description: String = "GitLab merge request provider. Supports gitlab.com and self-hosted instances."
    override val hoverDocumentation: String = """
        # gitlab

        **GitLab Provider**

        Fetches merge request data from GitLab API (v4). Supports GitLab.com and self-hosted instances.

        ## Properties
        - `url` — GitLab instance URL (default: `https://gitlab.com`)
        - `token` — API token (`GITLAB_TOKEN` or `CI_JOB_TOKEN`)
    """.trimIndent()

    internal val client = ReinitializableClient<GitLabClient> { it.close() }
    private var logger: Logger = NoOpLogger
    private val hasApproveToken = MutableStateFlow(false)
    private val deleteInlineStrategyState = MutableStateFlow(DeleteInlineStrategy.DISCUSSION)
    internal val deleteInlineStrategy: DeleteInlineStrategy get() = deleteInlineStrategyState.value

    override suspend fun defaultApiUrl(): String = DEFAULT_BASE_URL

    override suspend fun authTokenHint(): String = "GITLAB_TOKEN or CI_JOB_TOKEN"

    override fun permissionScope(): String =
        "Required: Developer role (or higher) on the target project." +
            "\nToken scopes needed: 'api' or 'read_api'."

    override fun tokenLocationHint(): String = "Verify your token at: Settings > Access Tokens."

    override fun notFoundGuidance(): String =
        "GitLab returns 404 instead of 403 for private projects you cannot access." +
            "\nVerify project visibility and that your token has at least Reporter role."

    override suspend fun initializeTyped(
        context: PluginContext,
        config: GitLabConfig
    ): Boolean {
        // KarandashJson coerces an unknown enum value to the default, so a typo would silently mean `discussion`.
        val rawStrategy = ((context.pluginConfig as? JsonObject)?.get("deleteInlineStrategy") as? JsonPrimitive)?.contentOrNull
        if (rawStrategy != null && DeleteInlineStrategy.entries.none { it.name.equals(rawStrategy, ignoreCase = true) }) {
            context.warn("$id: deleteInlineStrategy must be discussion or note, got '$rawStrategy'")
            return false
        }
        deleteInlineStrategyState.value = config.deleteInlineStrategy
        return initializeWithCredentials(
            context = context,
            platformName = PLATFORM_NAME,
            moduleType = "platform service",
            resolveCredentials = { GitLabCredentials.resolve(context.pluginConfig, context::env) },
            createClient = { creds ->
                logger = context.logger
                hasApproveToken.value = creds.approveToken != null
                client.rebuild(listOf(creds, context.allowLocalhost)) {
                    GitLabClient(
                        baseUrl = creds.baseUrl,
                        token = creds.token,
                        allowLocalhost = context.allowLocalhost,
                        logger = context.logger,
                        approveToken = creds.approveToken,
                        engine = engine,
                        jobToken = creds.jobToken,
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
        val mrIid = requireMrIdAsInt(mrId)
        val gitlabClient = checkNotNull(client.value) { "Gitlab not initialized" }
        return coroutineScope {
            val mrDeferred = async { gitlabClient.fetchMergeRequest(projectId, mrIid) }
            val commitsDeferred = async { PaginationProbe.probe { gitlabClient.fetchCommits(projectId, mrIid) } }
            val diffsDeferred = async { gitlabClient.fetchChanges(projectId, mrIid) }
            val approvalsDeferred = async { gitlabClient.fetchApprovals(projectId, mrIid) }
            val mr = mrDeferred.await().copy(approvedBy = approvalsDeferred.await())
            val jobs = mr.headPipeline
                ?.id
                ?.let { gitlabClient.fetchPipelineJobs(projectId, it) }
                .orEmpty()
            GitLabAdapter
                .toMergeRequest(mr, commitsDeferred.await().first, diffsDeferred.await())
                .copy(
                    ciJobs = jobs.map { CiJobStatus(name = it.name, status = it.status) },
                    commitsTruncated = commitsDeferred.await().second,
                )
        }
    }

    override fun invalidMergeRequestUrl(url: String): Nothing = throw IllegalArgumentException("Invalid GitLab MR URL: $url")

    override suspend fun rateLimitStatus(): RateLimitInfo? = client.value?.lastRateLimit()

    override suspend fun listOpenMergeRequestIds(
        projectId: String,
        targetBranch: String,
        limit: Int,
    ): List<String> = withInitializedClient(client.value, PLATFORM_NAME) { it.listOpenMergeRequestIids(projectId, targetBranch, limit) }

    override suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit> =
        withInitializedClient(client.value, PLATFORM_NAME) { it.searchMergeRequests(projectId, text, author, limit) }

    override suspend fun fetchFileBlame(
        projectId: String,
        filePath: String,
        ref: String
    ): List<BlameEntry> {
        val gitlabClient = client.value ?: return emptyList()
        return bestEffort(
            "GitLab fetchFileBlame",
            emptyList()
        ) { GitLabAdapter.toBlameEntries(gitlabClient.fetchBlame(projectId, filePath, ref)) }
    }

    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? = withInitializedClient(client.value, PLATFORM_NAME) { it.fetchFileContent(projectId, ref, path) }

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? {
        if (env("GITLAB_CI") != "true") {
            return ForgeCiEnvironment.woodpecker(env, id, setOf(ID))
                ?: ForgeCiEnvironment.codeBuild(env, id, DEFAULT_BASE_URL, ::parseGitRemoteUrl)
        }
        val projectId = env("CI_PROJECT_ID").takeUnless { it.isNullOrBlank() }
            ?: env("CI_PROJECT_PATH").takeUnless { it.isNullOrBlank() }
        val mrId = env("CI_MERGE_REQUEST_IID")
        val token = env("CI_JOB_TOKEN")
        val baseUrl = env("CI_SERVER_URL")
            ?: env("CI_API_V4_URL")?.removeSuffix("/api/v4")?.removeSuffix("/api/v4/")
            ?: DEFAULT_BASE_URL
        return CIEnvironment(platform = id, projectId = projectId, mrId = mrId, token = token, baseUrl = baseUrl)
    }

    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? {
        val match = PARSED_URL_REGEX.find(url) ?: return null
        val host = match.groupValues[1]
        val projectPath = match.groupValues[2]
        val mrId = match.groupValues[3]
        return ParsedMergeRequestUrl(platform = id, baseUrl = "https://$host", projectId = projectPath, mrId = mrId)
    }

    override suspend fun parseGitRemoteUrl(url: String): String? {
        val sshMatch = SSH_REGEX.find(url)
        if (sshMatch != null && url.contains("gitlab", ignoreCase = true)) {
            return sshMatch.groupValues[1]
        }
        return HTTPS_REGEX.find(url)?.groupValues?.get(1)
    }

    // ── Comment methods ──

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String
    ): String =
        withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
            val note = gitlabClient.postNote(projectId, requireMrIdAsInt(mrId), body)
            note.id.toString()
        }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
        gitlabClient.updateNote(projectId, requireMrIdAsInt(mrId), commentId.toLong(), body)
    }

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String
    ): String? =
        withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
            val notes = gitlabClient.listNotes(projectId, requireMrIdAsInt(mrId)).filter { !it.system }
            // the note that opens with the marker; the plain `contains` match is the v1.41.1 form, kept one release
            (notes.firstOrNull { AdapterHelpers.isSummaryComment(it.body, marker) } ?: notes.firstOrNull { it.body.contains(marker) })
                ?.id
                ?.toString()
        }

    override suspend fun renderSuggestionBlock(suggestedCode: String): String = "$SUGGESTION_FENCE$suggestedCode\n```"

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String =
        withDiscussions { discussions ->
            val discussion = discussions.createDiffDiscussion(
                projectId = projectId,
                mrIid = requireMrIdAsInt(mrId),
                body = multiLineSuggestions(body, line, startLine),
                baseSha = diffRefs.baseSha,
                startSha = diffRefs.startSha ?: diffRefs.baseSha,
                headSha = diffRefs.headSha,
                newPath = filePath,
                newLine = line,
                oldPath = filePath,
            )
            discussion.id
        }

    /** All [comments] as draft notes published together (one notification); returns the MR iid. */
    override suspend fun postInlineComments(
        projectId: String,
        mrId: String,
        comments: List<InlineCommentData>,
        diffRefs: DiffRefs,
    ): String =
        withDiscussions { discussions ->
            val drafts = comments.map {
                GitLabCreateDraftNoteRequest(
                    note = multiLineSuggestions(it.body, it.line, it.startLine),
                    position = GitLabDiffPosition(
                        baseSha = diffRefs.baseSha,
                        startSha = diffRefs.startSha ?: diffRefs.baseSha,
                        headSha = diffRefs.headSha,
                        oldPath = it.filePath,
                        newPath = it.filePath,
                        newLine = it.line,
                    ),
                )
            }
            discussions.publishDraftNotes(projectId, requireMrIdAsInt(mrId), drafts)
            mrId
        }

    /** Marked notes a reviewer resolved or thumbed down (award emoji need a call per note). */
    override suspend fun listDismissedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> = withDiscussions { it.listDismissedInlineComments(projectId, requireMrIdAsInt(mrId), marker) }

    override suspend fun listReviewThreads(
        projectId: String,
        mrId: String,
    ): List<ReviewThread> = withDiscussions { it.listReviewThreads(projectId, requireMrIdAsInt(mrId)) }

    override suspend fun fetchFindingFeedback(
        projectId: String,
        mrId: String,
    ): Result<List<FindingFeedback>> = suspendCatching { withDiscussions { it.listFindingFeedback(projectId, requireMrIdAsInt(mrId)) } }

    override suspend fun resolveThread(
        projectId: String,
        mrId: String,
        threadId: String,
        note: String?,
    ): Result<Unit> = suspendCatching { withDiscussions { it.resolveThread(projectId, requireMrIdAsInt(mrId), threadId, note) } }

    override suspend fun replyToThread(
        projectId: String,
        mrId: String,
        threadId: String,
        body: String,
    ): Result<Unit> = suspendCatching { withDiscussions { it.replyToThread(projectId, requireMrIdAsInt(mrId), threadId, body) } }

    override suspend fun fetchApprovalSignals(
        projectId: String,
        mrId: String,
    ): List<ApprovalSignal> = withDiscussions { it.listApprovalSignals(projectId, requireMrIdAsInt(mrId)) }

    override suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean = withDiscussions { it.isProjectMember(projectId, username) }

    override suspend fun currentUsername(): String = withDiscussions { it.currentUsername() }

    override suspend fun updateNote(
        projectId: String,
        mrId: String,
        threadId: String,
        noteId: String,
        body: String,
    ): Result<Unit> =
        suspendCatching { withDiscussions { it.updateNote(projectId, requireMrIdAsInt(mrId), threadId, noteId.toLong(), body) } }

    override suspend fun resolveMarked(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int = withDiscussions { it.resolveMarkedThreads(projectId, requireMrIdAsInt(mrId), marker, filePaths) }

    override suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String
    ): List<String> =
        withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
            gitlabClient.findAllNotesWithMarker(projectId, requireMrIdAsInt(mrId), marker)
        }

    override suspend fun listMarkedComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
            // Notes come newest first (GitLab's default `created_at desc`).
            gitlabClient.listNotes(projectId, requireMrIdAsInt(mrId)).filter { !it.system && marker in it.body }.map { it.body }
        }

    override suspend fun deleteComment(
        projectId: String,
        mrId: String,
        commentId: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
        gitlabClient.deleteNote(projectId, requireMrIdAsInt(mrId), commentId.toLong())
    }

    override suspend fun deleteMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String
    ): Int =
        if (deleteInlineStrategy == DeleteInlineStrategy.NOTE) {
            withDiscussions { it.deleteMarkedMrNotes(projectId, requireMrIdAsInt(mrId), marker) }
        } else {
            deleteMarkedInlineCommentsViaDiscussion(projectId, mrId, marker)
        }

    override suspend fun addReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) {
                GitLabReviewersClient(it).addReviewers(projectId, requireMrIdAsInt(mrId), usernames)
            }
        }

    override suspend fun removeReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) {
                GitLabReviewersClient(it).removeReviewers(projectId, requireMrIdAsInt(mrId), usernames)
            }
        }

    override suspend fun checkAuth(): Boolean? =
        withInitializedClient(client.value, PLATFORM_NAME) { it.checkAuth("${it.baseUrl}/api/v4/user") }

    override suspend fun supports(operation: PlatformOperation): Boolean = operation in SUPPORTED

    /** A commit status on the head commit: the verdict and title only, no annotations. */
    override suspend fun publishResultStatus(
        projectId: String,
        mrId: String,
        status: ResultStatus,
    ) = withInitializedClient(client.value, PLATFORM_NAME) { it.publishCommitStatus(projectId, status) }

    override suspend fun mergeMr(
        projectId: String,
        mrId: String,
        options: MergeOptions,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
                val body = buildJsonObject {
                    put("squash", options.squash)
                    put("should_remove_source_branch", options.deleteSourceBranch)
                    options.commitMessage?.let { put("merge_commit_message", it) }
                }
                gitlabClient.mergeMergeRequest(projectId, requireMrIdAsInt(mrId), body)
            }
        }

    override suspend fun closeMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
                gitlabClient.updateMergeRequest(projectId, requireMrIdAsInt(mrId), buildJsonObject { put("state_event", "close") })
            }
        }

    override suspend fun reopenMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
                gitlabClient.updateMergeRequest(projectId, requireMrIdAsInt(mrId), buildJsonObject { put("state_event", "reopen") })
            }
        }

    // ── Mutator methods ──

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
        val mrIid = requireMrIdAsInt(mrId)
        val currentMr = gitlabClient.fetchMergeRequest(projectId, mrIid)
        val updatedLabels = (currentMr.labels + labels).distinct()
        gitlabClient.updateMergeRequest(projectId, mrIid, buildJsonObject { put("labels", updatedLabels.joinToString(",")) })
    }

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
        val mrIid = requireMrIdAsInt(mrId)
        val currentMr = gitlabClient.fetchMergeRequest(projectId, mrIid)
        val updatedLabels = currentMr.labels - labels.toSet()
        gitlabClient.updateMergeRequest(projectId, mrIid, buildJsonObject { put("labels", updatedLabels.joinToString(",")) })
    }

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean
    ) = withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
        val mrIid = requireMrIdAsInt(mrId)
        val currentMr = gitlabClient.fetchMergeRequest(projectId, mrIid)
        val bareTitle = currentMr.title.replace(DRAFT_PREFIX, "")
        val newTitle = if (draft) {
            if (currentMr.title == bareTitle) "Draft: $bareTitle" else currentMr.title
        } else {
            bareTitle
        }
        gitlabClient.updateMergeRequest(projectId, mrIid, buildJsonObject { put("title", newTitle) })
    }

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
        gitlabClient.updateMergeRequest(projectId, requireMrIdAsInt(mrId), buildJsonObject { put("title", title) })
    }

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
        gitlabClient.updateMergeRequest(projectId, requireMrIdAsInt(mrId), buildJsonObject { put("description", description) })
    }

    override suspend fun approve(
        projectId: String,
        mrId: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
        if (!hasApproveToken.value) {
            logger.warn(
                "Auto-approve skipped: no approve token configured" +
                    " — set 'approveToken' in config or GITLAB_APPROVE_TOKEN env var",
            )
            return@withInitializedClient
        }
        val mrIid = requireMrIdAsInt(mrId)
        if (gitlabClient.isApprovedByCurrentUser(projectId, mrIid)) {
            logger.debug("Skipping approve: already approved by current user")
            return@withInitializedClient
        }
        gitlabClient.approveMergeRequest(projectId, mrIid)
    }

    override suspend fun unapprove(
        projectId: String,
        mrId: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { gitlabClient ->
        if (!hasApproveToken.value) {
            logger.warn(
                "Auto-unapprove skipped: no approve token configured" +
                    " — set 'approveToken' in config or GITLAB_APPROVE_TOKEN env var",
            )
            return@withInitializedClient
        }
        val mrIid = requireMrIdAsInt(mrId)
        if (!gitlabClient.isApprovedByCurrentUser(projectId, mrIid)) {
            logger.debug("Skipping unapprove: not currently approved by current user")
            return@withInitializedClient
        }
        gitlabClient.unapproveMergeRequest(projectId, mrIid)
    }

    // ── Private helpers ──

    private suspend fun deleteMarkedInlineCommentsViaDiscussion(
        projectId: String,
        mrId: String,
        marker: String
    ): Int = withDiscussions { it.deleteMarkedNotes(projectId, requireMrIdAsInt(mrId), marker) }

    private suspend fun <R> withDiscussions(block: suspend (GitLabDiscussionsClient) -> R): R =
        withInitializedClient(client.value, PLATFORM_NAME) { block(GitLabDiscussionsClient(it, logger)) }
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
    PlatformOperation.RESOLVE_DISCUSSIONS,
    PlatformOperation.REVIEW_THREADS,
    PlatformOperation.FILE_CONTENT,
    PlatformOperation.SEARCH,
    PlatformOperation.LIST_OPEN,
    PlatformOperation.APPROVAL_SIGNALS,
    PlatformOperation.DISMISSED_INLINE,
    PlatformOperation.SUGGESTIONS,
)
