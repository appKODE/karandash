@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.awscodecommit

import io.karandash.api.PluginContext
import io.karandash.api.finding.ReviewThread
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.api.platform.PlatformCloseable
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.RateLimitInfo
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.api.support.suspendCatching
import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSpec
import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.crypto.AwsCredentialChain
import io.karandash.models.ApprovalSignal
import io.karandash.models.BlameEntry
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.pagination.PaginationProbe
import io.karandash.providers.common.AdapterHelpers
import io.karandash.providers.common.SupersededInlineComment
import io.karandash.providers.common.UnifiedDiff
import io.karandash.providers.common.initializeWithCredentials
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.platform.ReinitializableClient
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/**
 * Unified AWS CodeCommit platform service module.
 *
 * Provides pull request fetching, comment posting, and mutation capabilities
 * for AWS CodeCommit. Requires beforeCommitId and afterCommitId for comment operations.
 *
 * Supported mutations: title, description, approve/unapprove, merge/close/reopen.
 * Not supported: labels (no labels concept), draft state (not supported by AWS).
 */
class AwsCodeCommitPlatformService internal constructor(
    private val credentialChain: (Logger) -> AwsCredentialChain,
    private val engine: HttpClientEngine? = null,
) : AbstractTypedPlatformServiceModule<AwsCodeCommitConfig>(AwsCodeCommitConfig.serializer()) {
    constructor() : this({ logger -> AwsCredentialChain(logger) })

    /** A service whose every client uses [engine]: a test MockEngine, also for tests in other modules. */
    constructor(engine: HttpClientEngine?) : this({ logger -> AwsCredentialChain(logger) }, engine)

    companion object {
        const val ID = "awscodecommit"
        const val CACHE_NAME = "aws-pr-data"
        private const val PLATFORM_NAME = "AWS CodeCommit"
        private const val NO_TARGETS = "Pull request has no targets"
        private const val NO_SOURCE_COMMIT = "Source commit not found"
        private const val NO_DESTINATION_COMMIT = "Destination commit not found"
        private const val CODECOMMIT = "codecommit"
        private const val MAX_PR_CACHE_SIZE = 256
        private val URL_REGEX = Regex(""".*/repositories/([^/]+)/pull-requests/(\d+)""")
        private val PR_ID_REGEX = Regex("""pr/(\d+)""")
        private val REPO_PR_REGEX = Regex(""".*/repositories/(.+)/pull-requests/(\d+)""")
        private val AWS_REGION_CONSOLE_REGEX = Regex("""https://([^.]+)\.console\.aws\.amazon\.com""")
        private val GIT_HTTPS_REMOTE_REGEX =
            Regex("""https?://git-codecommit\.[^.]+\.amazonaws\.com/v1/repos/([^/?]+?)(?:\.git)?$""")
        private val GIT_SSH_REMOTE_REGEX =
            Regex("""ssh://git-codecommit\.[^.]+\.amazonaws\.com/v1/repos/([^/?]+?)(?:\.git)?$""")
    }

    override val id: String = ID
    override val name: String = "AWS CodeCommit"
    override val version: String = "1.7.0"
    override val defaultPriority: Int = 60
    override val dslName: String = "awsCodeCommit"
    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    override val description: String = "AWS CodeCommit pull request provider. Self-hosted (requires AWS credentials)."

    override suspend fun aliases(): Set<String> = setOf(CODECOMMIT)

    override suspend fun authTokenHint(): String =
        "AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY (optional AWS_SESSION_TOKEN), or AWS_PROFILE / ~/.aws/credentials"

    override fun tokenLocationHint(): String = "Verify which identity the credentials resolve to with: aws sts get-caller-identity."

    override fun notFoundGuidance(): String =
        "CodeCommit reports a missing repository or pull request as a *DoesNotExistException." +
            "\nVerify the repository name and that the region matches the repository."

    /** CodeCommit has no CI status, merge-conflict flag, reviewer list, or labels on its pull request API. */
    override fun unavailableMergeRequestFields(): Set<MergeRequestField> =
        setOf(
            MergeRequestField.CI_STATUS,
            MergeRequestField.CI_JOBS,
            MergeRequestField.CONFLICTS,
            MergeRequestField.REVIEWERS,
            MergeRequestField.LABELS
        )

    internal val client = ReinitializableClient<AwsCodeSuiteClient> { it.close() }
    private val credentials = MutableStateFlow(AwsClient())
    private val cachedPrData = MutableStateFlow<TieredCache<String, PrData>?>(null)
    private var logger: Logger = NoOpLogger

    override suspend fun initializeTyped(
        context: PluginContext,
        config: AwsCodeCommitConfig
    ): Boolean {
        logger = context.logger
        initializePrCache(context)
        return initializeWithCredentials(
            context = context,
            platformName = PLATFORM_NAME,
            moduleType = "platform service",
            resolveCredentials = { AwsCredentials.resolve(context.pluginConfig, credentialChain(context.logger), context.logger) },
            createClient = { creds ->
                credentials.value = AwsClient(creds.region, creds.accessKeyId, creds.secretAccessKey)
                client.rebuild(listOf(creds, context.allowLocalhost)) {
                    AwsCodeSuiteClient(
                        region = creds.region,
                        accessKeyId = creds.accessKeyId,
                        secretAccessKey = creds.secretAccessKey,
                        sessionToken = creds.sessionToken,
                        allowLocalhost = context.allowLocalhost,
                        logger = context.logger,
                        endpoint = creds.endpoint,
                        stsEndpoint = creds.stsEndpoint,
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
        val activeClient = client.value ?: error("AWS CodeCommit not initialized")
        val pullRequest = activeClient.fetchPullRequest(mrId)
        val target = pullRequest.pullRequestTargets.firstOrNull()
            ?: throw IllegalArgumentException(NO_TARGETS)
        val repositoryName = target.repositoryName
        val sourceCommit = target.sourceCommit
            ?: throw IllegalArgumentException(NO_SOURCE_COMMIT)
        val destinationCommit = target.destinationCommit
            ?: throw IllegalArgumentException(NO_DESTINATION_COMMIT)
        return coroutineScope {
            val commitsDeferred = async {
                PaginationProbe.probe {
                    activeClient.fetchCommitsBetween(repositoryName, target.mergeBase ?: destinationCommit, sourceCommit)
                }
            }
            val differencesDeferred = async {
                activeClient.fetchDifferences(repositoryName, destinationCommit, sourceCommit)
            }
            val diffsDeferred = async { blobDiffs(activeClient, repositoryName, differencesDeferred.await()) }
            val approvalsDeferred = async {
                if (pullRequest.revisionId != null) {
                    activeClient.fetchApprovals(mrId, pullRequest.revisionId)
                } else {
                    emptyList()
                }
            }
            val mr = AwsCodeSuiteAdapter.toMergeRequest(
                pullRequest,
                commitsDeferred.await().first,
                differencesDeferred.await(),
                approvalsDeferred.await(),
            )
            mr.copy(
                changes = UnifiedDiff.attach(mr.changes, diffsDeferred.await()),
                commitsTruncated = commitsDeferred.await().second,
            )
        }
    }

    /** Diffs built from each difference's before/after blobs; a blob that fails to load skips its file. */
    private suspend fun blobDiffs(
        client: AwsCodeSuiteClient,
        repositoryName: String,
        differences: List<AwsDifference>,
    ): Map<String, String> {
        val blob: suspend (String?) -> String? = { id -> if (id == null) "" else client.fetchBlob(repositoryName, id) }
        return UnifiedDiff.fromContents(differences, { it.afterBlob?.path ?: it.beforeBlob?.path ?: "unknown" }) { difference ->
            val before = blob(difference.beforeBlob?.blobId)
            val after = blob(difference.afterBlob?.blobId)
            if (before != null && after != null) before to after else null
        }
    }

    override suspend fun listOpenMergeRequestIds(
        projectId: String,
        targetBranch: String,
        limit: Int,
    ): List<String> =
        withInitializedClient(client.value, PLATFORM_NAME) { c ->
            // Newest (highest id) first; each candidate is read to check its destination.
            val destination = "refs/heads/${targetBranch.removePrefix("refs/heads/")}"
            val matching = mutableListOf<String>()
            for (id in c.listPullRequestIds(projectId).sortedByDescending { it.toLongOrNull() ?: 0L }) {
                if (matching.size >= limit) break
                if (c.fetchPullRequest(id).pullRequestTargets.any { it.destinationReference == destination }) matching += id
            }
            matching
        }

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest {
        val match = URL_REGEX.find(url)
            ?: throw IllegalArgumentException("Invalid AWS CodeCommit URL format: $url")
        val (repositoryName, pullRequestId) = match.destructured
        return fetchMergeRequest(repositoryName, pullRequestId)
    }

    override suspend fun fetchFileBlame(
        projectId: String,
        filePath: String,
        ref: String
    ): List<BlameEntry> = emptyList()

    override val supportsFileContent: Boolean = true

    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? = withInitializedClient(client.value, PLATFORM_NAME) { it.fetchFileContent(projectId, ref, path) }

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? {
        val region = env("AWS_REGION") ?: env("AWS_DEFAULT_REGION") ?: return null
        env("CODEBUILD_BUILD_ID") ?: return null
        val repoUrl = env("CODEBUILD_SOURCE_REPO_URL") ?: return null
        if (!repoUrl.contains(CODECOMMIT, ignoreCase = true)) return null
        val repoName = repoUrl.substringAfterLast("/")
        val mrId = env("CODEBUILD_SOURCE_VERSION")
            ?.let { PR_ID_REGEX.find(it)?.groupValues?.get(1) }
        val baseUrl = "https://codecommit.$region.amazonaws.com"
        return CIEnvironment(platform = id, projectId = repoName, mrId = mrId, token = null, baseUrl = baseUrl)
    }

    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? {
        val match = REPO_PR_REGEX.find(url) ?: return null
        if (!url.contains("aws", ignoreCase = true) && !url.contains(CODECOMMIT, ignoreCase = true)) return null
        val repositoryName = match.groupValues[1]
        val prId = match.groupValues[2]
        if (repositoryName.isBlank()) return null
        val regionMatch = AWS_REGION_CONSOLE_REGEX.find(url)
        val region = regionMatch?.groupValues?.get(1) ?: "us-east-1"
        return ParsedMergeRequestUrl(
            platform = id,
            baseUrl = "https://codecommit.$region.amazonaws.com",
            projectId = repositoryName,
            mrId = prId,
        )
    }

    override suspend fun parseGitRemoteUrl(url: String): String? {
        val httpsMatch = GIT_HTTPS_REMOTE_REGEX.find(url)
        if (httpsMatch != null) return httpsMatch.groupValues[1]
        return GIT_SSH_REMOTE_REGEX.find(url)?.groupValues?.get(1)
    }

    // ── Comment methods ──

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String
    ): String =
        withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
            val prData = getPrData(awsClient, projectId, mrId)
            val comment = awsClient.postCommentForPullRequest(
                repoName = prData.repositoryName,
                prId = mrId,
                content = body,
                beforeCommitId = prData.beforeCommitId,
                afterCommitId = prData.afterCommitId,
            )
            comment.commentId
        }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
        awsClient.updateComment(commentId, body)
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
        withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
            val prData = getPrData(awsClient, projectId, mrId)
            awsClient
                .getCommentsForPullRequest(prData.repositoryName, mrId)
                .filter { AdapterHelpers.isSummaryComment(it.content, marker) }
                .map { it.commentId }
        }

    override suspend fun listMarkedComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
            val prData = getPrData(awsClient, projectId, mrId)
            // Comments come oldest first.
            awsClient
                .getCommentsForPullRequest(prData.repositoryName, mrId)
                .mapNotNull { it.content }
                .filter { marker in it }
                .reversed()
        }

    /** CodeCommit keeps a deleted comment as an empty "deleted" placeholder. */
    override suspend fun deleteComment(
        projectId: String,
        mrId: String,
        commentId: String,
    ) = withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
        awsClient.deleteCommentContent(commentId)
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
        withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
            val prData = getPrData(awsClient, projectId, mrId)
            val comment = awsClient.postInlineCommentForPullRequest(
                repoName = prData.repositoryName,
                prId = mrId,
                content = body,
                beforeCommitId = diffRefs.baseSha.ifEmpty { prData.beforeCommitId },
                afterCommitId = diffRefs.headSha.ifEmpty { prData.afterCommitId },
                filePath = filePath,
                line = line,
            )
            comment.commentId
        }

    override suspend fun resolveMarked(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int = supersedeMarkedInlineComments(projectId, mrId, marker, filePaths)

    /** CodeCommit cannot resolve comments: marks earlier inline comments outdated in place, never deletes them. */
    private suspend fun supersedeMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int =
        withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
            val prData = getPrData(awsClient, projectId, mrId)
            val inline = awsClient.getCommentsForPullRequest(prData.repositoryName, mrId).filter { it.filePath != null }
            SupersededInlineComment.supersede(inline, marker, filePaths, logger, { it.content }, { it.filePath }) {
                awsClient.updateComment(it.commentId, SupersededInlineComment.body(it.content.orEmpty()))
            }
        }

    override suspend fun listReviewThreads(
        projectId: String,
        mrId: String,
    ): List<ReviewThread> =
        withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
            awsReviewThreads(awsClient.getCommentsForPullRequest(getPrData(awsClient, projectId, mrId).repositoryName, mrId))
        }

    /** [threadId] is the opening comment's id, as [listReviewThreads] reports it. */
    override suspend fun replyToThread(
        projectId: String,
        mrId: String,
        threadId: String,
        body: String,
    ): Result<Unit> = suspendCatching { withInitializedClient(client.value, PLATFORM_NAME) { it.postCommentReply(threadId, body) } }.map { }

    override suspend fun updateNote(
        projectId: String,
        mrId: String,
        threadId: String,
        noteId: String,
        body: String,
    ): Result<Unit> = suspendCatching { withInitializedClient(client.value, PLATFORM_NAME) { it.updateComment(noteId, body) } }

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
    ): Unit = throw UnsupportedPlatformOperationException("setDraft")

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
        awsClient.updatePullRequestTitle(mrId, title)
    }

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
        awsClient.updatePullRequestDescription(mrId, description)
    }

    override suspend fun approve(
        projectId: String,
        mrId: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
        val revisionId = awsClient.currentRevisionId(mrId)
        val isApproved = awsClient.isApprovedByCurrentCaller(mrId, revisionId)
        if (isApproved == true) {
            logger.debug("Skipping approve: already approved by current caller")
            return@withInitializedClient
        }
        awsClient.approvePullRequest(mrId, revisionId)
    }

    override suspend fun unapprove(
        projectId: String,
        mrId: String
    ) = withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
        val revisionId = awsClient.currentRevisionId(mrId)
        val isApproved = awsClient.isApprovedByCurrentCaller(mrId, revisionId)
        if (isApproved == false) {
            logger.debug("Skipping unapprove: not currently approved by current caller")
            return@withInitializedClient
        }
        awsClient.unapprovePullRequest(mrId, revisionId)
    }

    /** STS `GetCallerIdentity`; null without explicit credentials, since the request cannot be signed. */
    override suspend fun checkAuth(): Boolean? {
        val holder = credentials.value
        if (!holder.hasCredentials()) {
            logger.debug("AWS CodeCommit: no explicit credentials for region ${holder.getRegion()}; auth check skipped")
            return null
        }
        return withInitializedClient(client.value, PLATFORM_NAME) { it.checkCallerIdentity() }
    }

    override suspend fun supports(operation: PlatformOperation): Boolean = operation in SUPPORTED

    /** Always null: CodeCommit does not expose remaining quota; throttling shows up only as a `ThrottlingException`. */
    override suspend fun rateLimitStatus(): RateLimitInfo? = null

    /** Unknown (the SDK default): IAM has no repository membership API: access is granted by policy, not by a member list. */
    override suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean? {
        logger.debug("AWS CodeCommit: project membership cannot be determined, treating '$username' in '$projectId' as unknown")
        return null
    }

    /** The user part of the caller ARN. */
    override suspend fun currentUsername(): String? =
        withInitializedClient(client.value, PLATFORM_NAME) { c -> c.getCallerArn()?.let { AwsCodeSuiteAdapter.extractUser(it).username } }

    override suspend fun fetchApprovalSignals(
        projectId: String,
        mrId: String,
    ): List<ApprovalSignal> =
        withInitializedClient(client.value, PLATFORM_NAME) { c ->
            approvalSignals(c.getCommentsForPullRequest(getPrData(c, projectId, mrId).repositoryName, mrId))
        }

    override suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit> =
        withInitializedClient(client.value, PLATFORM_NAME) {
            searchPullRequests(it, projectId, text, author, limit)
        }

    /** Squash or three-way merge at the source tip; [MergeOptions.deleteSourceBranch] deletes the branch afterwards. */
    override suspend fun mergeMr(
        projectId: String,
        mrId: String,
        options: MergeOptions,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(client.value, PLATFORM_NAME) { awsClient ->
                val target = awsClient.fetchPullRequest(mrId).pullRequestTargets.firstOrNull()
                    ?: throw IllegalArgumentException(NO_TARGETS)
                val sourceCommit = requireNotNull(target.sourceCommit) { NO_SOURCE_COMMIT }
                awsClient.mergePullRequest(mrId, target.repositoryName, sourceCommit, options.squash, options.commitMessage)
                if (options.deleteSourceBranch) {
                    awsClient.deleteBranch(target.repositoryName, target.sourceReference.removePrefix("refs/heads/"))
                }
            }
        }

    override suspend fun closeMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = updateStatus(mrId, "CLOSED")

    override suspend fun reopenMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = updateStatus(mrId, "OPEN")

    override suspend fun shutdown() {
        super.shutdown()
        prCache().clear()
    }

    // ── Private helpers ──

    private suspend fun updateStatus(
        mrId: String,
        status: String,
    ): Result<Unit> = suspendCatching { withInitializedClient(client.value, PLATFORM_NAME) { it.updatePullRequestStatus(mrId, status) } }

    private fun initializePrCache(context: PluginContext) {
        val cm = context.cacheProvider as? CacheManager
        if (cm != null) {
            cachedPrData.value = cm.getOrCreate(
                CACHE_NAME,
                CacheSpec(maxEntries = MAX_PR_CACHE_SIZE, tier = CacheTier.MEMORY_ONLY),
            )
        }
    }

    private fun prCache(): TieredCache<String, PrData> {
        val existing = cachedPrData.value
        if (existing != null) return existing
        val fallback = TieredCache<String, PrData>(
            name = CACHE_NAME,
            maxEntries = MAX_PR_CACHE_SIZE,
            tier = CacheTier.MEMORY_ONLY,
        )
        cachedPrData.value = fallback
        return fallback
    }

    private suspend fun getPrData(
        awsClient: AwsCodeSuiteClient,
        projectId: String,
        mrId: String
    ): PrData {
        val cacheKey = "$projectId:$mrId"
        prCache().get(cacheKey)?.let { return it }
        val pullRequest = awsClient.fetchPullRequest(mrId)
        val target = pullRequest.pullRequestTargets.firstOrNull()
            ?: throw IllegalArgumentException(NO_TARGETS)
        val data = PrData(
            repositoryName = target.repositoryName,
            beforeCommitId = target.destinationCommit
                ?: throw IllegalArgumentException(NO_DESTINATION_COMMIT),
            afterCommitId = target.sourceCommit
                ?: throw IllegalArgumentException(NO_SOURCE_COMMIT),
        )
        prCache().put(cacheKey, data)
        return data
    }

    @Serializable
    private data class PrData(
        val repositoryName: String,
        val beforeCommitId: String,
        val afterCommitId: String,
    )
}

/** The operations this platform implements; everything else is [PlatformOperation] unsupported. */
private val SUPPORTED: Set<PlatformOperation> = setOf(
    PlatformOperation.APPROVE,
    PlatformOperation.COMMENTS,
    PlatformOperation.MERGE,
    PlatformOperation.REOPEN,
    PlatformOperation.POST_INLINE_COMMENTS,
    PlatformOperation.FILE_CONTENT,
    PlatformOperation.LIST_OPEN,
    PlatformOperation.REVIEW_THREADS,
    PlatformOperation.APPROVAL_SIGNALS,
    PlatformOperation.SEARCH,
)

/** LSP hover text for this module's config block. */
private val HOVER_DOCUMENTATION = """
    # awsCodeCommit

    **AWS CodeCommit Provider**

    Fetches pull request data from AWS CodeCommit.

    ## Properties
    - `region: String` - AWS region
    - `repositoryName: String` - Repository name
""".trimIndent()
