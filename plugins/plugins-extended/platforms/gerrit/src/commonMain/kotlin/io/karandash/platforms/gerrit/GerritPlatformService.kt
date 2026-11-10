@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gerrit

import io.karandash.api.PluginContext
import io.karandash.api.finding.InlineCommentData
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.InlineCommentBatchPoster
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.api.platform.PlatformCloseable
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.RateLimitInfo
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.suspendCatching
import io.karandash.models.ApprovalSignal
import io.karandash.models.BlameEntry
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.providers.common.AdapterHelpers
import io.karandash.providers.common.SupersededInlineComment
import io.karandash.providers.common.UnifiedDiff
import io.karandash.providers.common.initializeWithCredentials
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.platform.ReinitializableClient
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.io.encoding.Base64

/**
 * Unified Gerrit platform service module.
 *
 * Gerrit uses review messages rather than inline comments for validation results.
 * Comments cannot be edited in Gerrit, so updateComment posts a new review message.
 * Labels are implemented via hashtags; draft status via Work-In-Progress flag.
 * @param engine HTTP engine of every client this service builds, for tests (a MockEngine, also from other modules);
 *   production leaves it null, the platform engine.
 */
class GerritPlatformService(
    private val engine: HttpClientEngine? = null,
) : AbstractTypedPlatformServiceModule<GerritConfig>(GerritConfig.serializer()),
    InlineCommentBatchPoster {
    companion object {
        const val ID = "gerrit"
        private const val PLATFORM_NAME = "Gerrit"
        private const val GERRIT_APPROVE_VOTE = 2
        private val MODERN_URL_REGEX = Regex("""/c/(.+)/\+/(\d+)""")
        private val LEGACY_URL_REGEX = Regex("""#/?(\d+)""")
        private val PARSE_MR_URL_REGEX = Regex("""https?://([^/]+)/c/(.+)/\+/(\d+)""")
        private val GIT_SSH_REMOTE_REGEX = Regex("""ssh://(?:[^@]+@)?([^/:]+)(?::\d+)?/(.+?)(?:\.git)?$""")
        private val GIT_HTTPS_REMOTE_REGEX = Regex("""https?://[^/]*gerrit[^/]*/(?:a/)?(.+?)(?:\.git)?$""")
    }

    override val id: String = ID
    override val name: String = "Gerrit"
    override val version: String = "1.7.0"
    override val defaultPriority: Int = 40
    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    override val description: String = "Gerrit code review provider. Self-hosted only."

    override suspend fun authTokenHint(): String = "GERRIT_TOKEN"

    /** Gerrit reports CI as votes on the change (an aggregate status), not as individual jobs. */
    override fun unavailableMergeRequestFields(): Set<MergeRequestField> = setOf(MergeRequestField.CI_JOBS)

    override fun permissionScope(): String = "Required: read access to the project and change."

    override fun tokenLocationHint(): String = "Verify your HTTP password at: Settings > HTTP Credentials (used with GERRIT_USERNAME)."

    override fun notFoundGuidance(): String =
        "Gerrit returns 404 for changes your account cannot see." +
            "\nVerify the project and change number, and that the account can read the change."

    private val configuredToken = MutableStateFlow<String?>(null)

    /** Sends through [commentClient]'s HTTP client (credential scoped to its host), so it is rebuilt with it. */
    internal val providerClient = ReinitializableClient<GerritProviderClient> { }
    internal val commentClient = ReinitializableClient<GerritClient> { it.close() }
    private var logger: Logger = NoOpLogger

    override suspend fun initializeTyped(
        context: PluginContext,
        config: GerritConfig
    ): Boolean {
        logger = context.logger
        return initializeWithCredentials(
            context = context,
            platformName = PLATFORM_NAME,
            moduleType = "platform service",
            credentialsOptional = true, // anonymous reads work; mutating client calls fail fast without a token
            resolveCredentials = { GerritCredentials.resolve(context.pluginConfig, context::env) },
            createClient = { creds ->
                if (creds.token != null && creds.username == null) {
                    context.logger.warn(
                        "Gerrit token is set without a username (config 'username' or GERRIT_USERNAME); " +
                            "Gerrit needs HTTP Basic username:http-password, falling back to anonymous access",
                    )
                }
                val token = creds.token ?: configuredToken.value
                configuredToken.value = token
                val authToken = token.takeIf { creds.username != null }
                val comment = commentClient.rebuild(listOf(creds.baseUrl, creds.username, token, context.allowLocalhost, config.notify)) {
                    GerritClient(
                        creds.baseUrl,
                        authToken,
                        allowLocalhost = context.allowLocalhost,
                        logger = context.logger,
                        username = creds.username,
                        notify = config.notify,
                        engine = engine,
                    )
                }
                providerClient.rebuild(comment) {
                    GerritProviderClient(
                        creds.baseUrl,
                        comment.sharedHttpClient,
                        authToken,
                        allowLocalhost = context.allowLocalhost,
                        username = creds.username,
                        logger = context.logger,
                    )
                }
                setManagedCloseable(
                    PlatformCloseable {
                        providerClient.close()
                        commentClient.close()
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
        val activeClient = providerClient.value ?: error("Gerrit not initialized")
        return activeClient.listOpenChangeNumbers(projectId, targetBranch, limit)
    }

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest {
        val activeClient = providerClient.value ?: error("Gerrit not initialized")
        val change = activeClient.fetchChange(projectId, mrId)
        val commits = change.currentRevision?.let { revisionId ->
            change.revisions[revisionId]?.commit?.let { listOf(it) }
        } ?: emptyList()
        val mr = GerritAdapter.toMergeRequest(change, commits)
        val patch = change.currentRevision?.let { activeClient.fetchPatch(projectId, mrId, it) }?.let(::decodePatch)
        return mr.copy(changes = UnifiedDiff.attach(mr.changes, patch?.let(UnifiedDiff::splitByFile).orEmpty()))
    }

    private fun decodePatch(body: String): String? =
        try {
            Base64.decode(body.trim()).decodeToString()
        } catch (e: IllegalArgumentException) {
            logger.warn("Gerrit revision patch is not base64 (${e::class.simpleName}); file diffs are unavailable")
            null
        }

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest {
        val (project, changeId) = parseGerritUrl(url)
        return fetchMergeRequest(project, changeId)
    }

    // fetchFileBlame: SDK default (empty list). Gerrit REST has blame only for a change revision
    // (`/changes/{id}/revisions/{rev}/files/{file}/blame`), not for a file at a branch or commit.

    override suspend fun currentUsername(): String? =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { it.fetchCurrentUsername() }

    /** Unknown (the SDK default): `check.access` needs the global View Access capability and a ref. */
    override suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean? {
        logger.debug("Gerrit: project membership cannot be determined, treating '$username' in '$projectId' as unknown")
        return null
    }

    override suspend fun fetchApprovalSignals(
        projectId: String,
        mrId: String,
    ): List<ApprovalSignal> = withInitializedClient(commentClient.value, PLATFORM_NAME) { approvalSignals(it.listComments(mrId)) }

    override suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit> =
        withInitializedClient(providerClient.value, PLATFORM_NAME) { client ->
            client.searchChanges(projectId, text, author, limit).map { it.toSearchHit() }
        }

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? = GerritCredentials.detectCi(id, env)

    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? {
        val match = PARSE_MR_URL_REGEX.find(url) ?: return null
        val host = match.groupValues[1]
        val project = match.groupValues[2]
        val changeId = match.groupValues[3]
        if (project.isBlank()) return null
        return ParsedMergeRequestUrl(platform = id, baseUrl = "https://$host", projectId = project, mrId = changeId)
    }

    override suspend fun parseGitRemoteUrl(url: String): String? {
        val sshMatch = GIT_SSH_REMOTE_REGEX.find(url)
        if (sshMatch != null && url.contains("gerrit", ignoreCase = true)) {
            return sshMatch.groupValues[2]
        }
        return GIT_HTTPS_REMOTE_REGEX.find(url)?.groupValues?.get(1)
    }

    // ── Comment methods ──

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String
    ): String =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
            gerritClient.postReview(mrId, body)
            mrId
        }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String
    ): Unit =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
            // change messages cannot be edited: post a new one only when the summary changed
            val current = gerritClient.listComments(mrId).find { it.id == commentId }
            if (current?.message?.contains(body) != true) gerritClient.postReview(mrId, body)
        }

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String
    ): String? =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
            // Gerrit prefixes each change message with "Patch Set N[: votes]" and a blank line; a human reply that only
            // quotes the marker is not the summary. Every update is a new message, so the newest one is current.
            gerritClient
                .listComments(mrId)
                .lastOrNull { AdapterHelpers.isSummaryComment(it.message.removePatchSetHeader(), marker) }
                ?.id
        }

    override suspend fun listMarkedComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
            // Change messages come oldest first, each behind its "Patch Set N" header.
            gerritClient
                .listComments(mrId)
                .map { it.message }
                .filter { marker in it }
                .map { it.removePatchSetHeader() }
                .reversed()
        }

    private fun String.removePatchSetHeader(): String = if (startsWith("Patch Set ")) substringAfter("\n\n", "") else this

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
            val revisionId = diffRefs.headSha
            gerritClient.postInlineComments(
                changeId = mrId,
                revisionId = revisionId,
                comments = mapOf(filePath to listOf(GerritInlineComment(line = line, message = body, range = lineRange(startLine, line)))),
            )
            mrId
        }

    /** All [comments] in one `POST .../review` on the head revision (one notification); returns the change id. */
    override suspend fun postInlineComments(
        projectId: String,
        mrId: String,
        comments: List<InlineCommentData>,
        diffRefs: DiffRefs,
    ): String =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
            gerritClient.postInlineComments(changeId = mrId, revisionId = diffRefs.headSha, comments = byFile(comments))
            mrId
        }

    /** [comments] with [summary] as the message of the same review: one ReviewInput, one notification. */
    override suspend fun postReviewWithSummary(
        projectId: String,
        mrId: String,
        comments: List<InlineCommentData>,
        diffRefs: DiffRefs,
        summary: String,
    ): Boolean =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
            gerritClient.postInlineComments(changeId = mrId, revisionId = diffRefs.headSha, comments = byFile(comments), message = summary)
            true
        }

    private fun byFile(comments: List<InlineCommentData>): Map<String, List<GerritInlineComment>> =
        comments.groupBy(
            { it.filePath }
        ) { GerritInlineComment(line = it.line, message = it.body, range = lineRange(it.startLine, it.line)) }

    override suspend fun resolveMarked(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int = resolveMarkedThreads(mrId, marker, filePaths)

    /**
     * Resolves instead of deleting: Gerrit's Delete Comment needs the Administrate Server capability and rewrites
     * history, which a review bot must not hold.
     */
    override suspend fun deleteMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int = resolveMarkedInlineComments(projectId, mrId, marker)

    /** Gerrit has no reactions: a marked thread counts as dismissed when someone else left it resolved. */
    override suspend fun listDismissedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { dismissedMarkedComments(it.listInlineComments(mrId), marker) }

    /**
     * Resolves this module's marked inline threads nobody answered yet with a reply marked `unresolved: false`, one
     * review per patch set. A thread with any reply is left to the people in it.
     */
    private suspend fun resolveMarkedThreads(
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
            val all = gerritClient.listInlineComments(mrId)
            val answered = all.mapNotNullTo(mutableSetOf()) { it.inReplyTo }
            val open = all.filter {
                it.inReplyTo == null && it.id !in answered && it.message?.contains(marker) == true &&
                    (filePaths == null || it.path in filePaths)
            }
            open.groupBy { it.patchSet }.forEach { (patchSet, comments) ->
                val replies = comments.groupBy({ checkNotNull(it.path) { "listInlineComments sets path" } }) {
                    GerritInlineComment(line = it.line ?: 0, message = SupersededInlineComment.NOTE, inReplyTo = it.id, unresolved = false)
                }
                gerritClient.postInlineComments(mrId, patchSet?.toString() ?: "current", replies)
            }
            open.size
        }

    /** Resolves the thread of comment [threadId] with a reply marked `unresolved: false` ([note], else "Done"). */
    override suspend fun resolveThread(
        projectId: String,
        mrId: String,
        threadId: String,
        note: String?,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
                val comment = requireNotNull(gerritClient.listInlineComments(mrId).find { it.id == threadId }) {
                    "Gerrit change $mrId has no comment $threadId"
                }
                val reply =
                    GerritInlineComment(line = comment.line ?: 0, message = note ?: "Done", inReplyTo = threadId, unresolved = false)
                val path = checkNotNull(comment.path) { "listInlineComments sets path" }
                gerritClient.postInlineComments(mrId, comment.patchSet?.toString() ?: "current", mapOf(path to listOf(reply)))
                Unit
            }
        }

    // ── Mutator methods ──

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
        // Hashtags are stored as given, so the labels read back from the change match the configured ones
        gerritClient.setHashtags(mrId, labels.map { it.trim() }, emptyList())
    }

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
        gerritClient.setHashtags(mrId, emptyList(), labels.map { it.trim() })
    }

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
        if (draft) {
            gerritClient.setWorkInProgress(mrId)
        } else {
            gerritClient.setReadyForReview(mrId)
        }
    }

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
        gerritClient.updateSubject(mrId, title)
    }

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
        gerritClient.updateDescription(mrId, description)
    }

    override suspend fun approve(
        projectId: String,
        mrId: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
        val currentVote = gerritClient.getCurrentCodeReviewVote(projectId, mrId)
        if (currentVote != null && currentVote >= GERRIT_APPROVE_VOTE) {
            logger.debug("Skipping approve: already approved by current user")
            return@withInitializedClient
        }
        gerritClient.approveChange(mrId)
        Unit
    }

    override suspend fun unapprove(
        projectId: String,
        mrId: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { gerritClient ->
        val currentVote = gerritClient.getCurrentCodeReviewVote(projectId, mrId)
        if (currentVote == null || currentVote == 0) {
            logger.debug("Skipping unapprove: not currently approved by current user")
            return@withInitializedClient
        }
        gerritClient.unapproveChange(mrId)
        Unit
    }

    override suspend fun checkAuth(): Boolean? = withInitializedClient(commentClient.value, PLATFORM_NAME) { it.checkAuth() }

    override val supportsFileContent: Boolean = true

    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? = withInitializedClient(commentClient.value, PLATFORM_NAME) { it.fetchFileContent(projectId, ref, path) }

    override suspend fun supports(operation: PlatformOperation): Boolean = operation in SUPPORTED

    /** Always null: Gerrit's REST API does not expose rate-limit state (no quota headers or endpoint). */
    override suspend fun rateLimitStatus(): RateLimitInfo? = null

    /** Submits the change; squash and commit message come from the project's submit type, so [options] is not sent. */
    override suspend fun addReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = forEachReviewer(usernames) { c, name -> c.addReviewer(mrId, name) }

    override suspend fun removeReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = forEachReviewer(usernames) { c, name -> c.deleteReviewer(mrId, name) }

    private suspend fun forEachReviewer(
        names: List<String>,
        call: suspend (GerritClient, String) -> Unit,
    ): Result<Unit> = suspendCatching { withInitializedClient(commentClient.value, PLATFORM_NAME) { c -> names.forEach { call(c, it) } } }

    override suspend fun mergeMr(
        projectId: String,
        mrId: String,
        options: MergeOptions,
    ): Result<Unit> {
        if (options != MergeOptions()) logger.warn("Gerrit: merge options are ignored; the project's submit type applies")
        return changeAction(mrId, "submit")
    }

    override suspend fun closeMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = changeAction(mrId, "abandon")

    override suspend fun reopenMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = changeAction(mrId, "restore")

    // ── Private helpers ──

    private suspend fun changeAction(
        mrId: String,
        action: String,
    ): Result<Unit> = suspendCatching { withInitializedClient(commentClient.value, PLATFORM_NAME) { it.changeAction(mrId, action) } }

    private fun parseGerritUrl(url: String): Pair<String, String> {
        val modernMatch = MODERN_URL_REGEX.find(url)
        if (modernMatch != null) {
            return Pair(modernMatch.groupValues[1], modernMatch.groupValues[2])
        }
        val legacyMatch = LEGACY_URL_REGEX.find(url)
        require(legacyMatch == null) {
            "Legacy Gerrit URL format requires project name. Use format: /c/{project}/+/{change-id}"
        }
        throw IllegalArgumentException(
            "Invalid Gerrit URL format: $url. Expected format: https://gerrit.example.com/c/{project}/+/{change-id}",
        )
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
    PlatformOperation.RESOLVE_DISCUSSIONS,
    PlatformOperation.LIST_OPEN,
    PlatformOperation.FILE_CONTENT,
    PlatformOperation.INLINE_RANGES,
    PlatformOperation.MANAGE_REVIEWERS,
    PlatformOperation.BATCH_INLINE,
    PlatformOperation.DISMISSED_INLINE,
    PlatformOperation.APPROVAL_SIGNALS,
    PlatformOperation.SEARCH,
)

/**
 * Bodies of the marked root comments whose thread's latest comment is resolved and written by an account other than
 * the root's author (the bot's own resolving reply shares the root's author, so it does not count).
 */
internal fun dismissedMarkedComments(
    comments: List<GerritPublishedComment>,
    marker: String,
): List<String> {
    val byId = comments.associateBy { it.id }
    val threads = comments.groupBy { comment -> generateSequence(comment) { c -> c.inReplyTo?.let(byId::get) }.last().id }
    return comments.filter { it.inReplyTo == null && it.message?.contains(marker) == true }.mapNotNull { root ->
        val latest = threads.getValue(root.id).maxBy { it.updated.orEmpty() }
        root.message.takeIf { latest.unresolved == false && latest.author?.accountId != root.author?.accountId }
    }
}

/**
 * Lines [startLine] to [endLine] as a range; null for a single line. The comment lands on [endLine]; the end is
 * exclusive and its length unknown here, so the highlight runs to the start of [endLine].
 */
internal fun lineRange(
    startLine: Int?,
    endLine: Int,
): GerritCommentRange? =
    // limit: highlight stops at the start of the last line; pass the line's length if a full-line highlight matters
    startLine?.takeIf { it < endLine }?.let { GerritCommentRange(it, 0, endLine, 0) }

/** LSP hover text for this module's config block. */
private val HOVER_DOCUMENTATION = """
    # gerrit

    **Gerrit Provider**

    Fetches change data from Gerrit Code Review.

    ## Properties
    - `url: String` - Gerrit instance URL
    - `username: String` - Gerrit username
    - `password: String` - HTTP password
""".trimIndent()
