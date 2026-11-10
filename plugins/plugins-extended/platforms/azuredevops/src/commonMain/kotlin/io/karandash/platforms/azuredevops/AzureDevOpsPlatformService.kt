@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.azuredevops

import io.karandash.api.PluginContext
import io.karandash.api.finding.ReviewThread
import io.karandash.api.platform.CIEnvironment
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
import io.karandash.providers.common.ProjectIdParser
import io.karandash.providers.common.UrlValidator
import io.karandash.providers.common.requireMrIdAsInt
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.platform.ReinitializableClient
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Unified Azure DevOps platform service module.
 *
 * Provides pull request fetching, comment posting via threads API,
 * and mutation capabilities for Azure DevOps.
 * @param engine HTTP engine of every client this service builds, for tests (a MockEngine, also from other modules);
 *   production leaves it null, the platform engine.
 */
class AzureDevOpsPlatformService(
    private val engine: HttpClientEngine? = null,
) : AbstractTypedPlatformServiceModule<AzureDevOpsConfig>(AzureDevOpsConfig.serializer()),
    ResultStatusPublisher {
    companion object {
        const val ID = "azure-devops"
        const val DEFAULT_BASE_URL = "https://dev.azure.com"
        private const val PLATFORM_NAME = "Azure DevOps"
        private const val PROJECT_ID_FORMAT = "organization/project/repositoryId"
        private const val MR_ID_LABEL = "PR number"
        private val DISMISSED_THREAD_STATUSES = setOf("wontFix", "byDesign")
        private val PR_PATH_REGEX = Regex("""^([^/]+)/([^/]+)/_git/([^/]+)/pullrequest/(\d+)""")
        private val PARSE_MR_URL_REGEX = Regex("""https?://dev\.azure\.com/([^/]+)/([^/]+)/_git/([^/]+)/pullrequest/(\d+)""")
        private val GIT_SSH_REMOTE_REGEX = Regex("""git@ssh\.dev\.azure\.com:v3/([^/]+)/([^/]+)/([^/]+?)(?:\.git)?$""")
        private val GIT_HTTPS_REMOTE_REGEX = Regex("""https?://(?:[^@]+@)?dev\.azure\.com/([^/]+)/([^/]+)/_git/([^/]+?)(?:\.git)?$""")
    }

    override val id: String = ID
    override val name: String = "Azure Devops"
    override val version: String = "1.7.0"
    override val defaultPriority: Int = 50
    override val dslName: String = "azureDevOps"
    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    override val description: String = "Azure DevOps pull request provider."

    override suspend fun aliases(): Set<String> = setOf("azure", "ado")

    override suspend fun defaultApiUrl(): String = DEFAULT_BASE_URL

    override suspend fun authTokenHint(): String = "AZURE_DEVOPS_TOKEN"

    override fun permissionScope(): String = "Required: 'Code (Read)' scope on the Azure DevOps PAT."

    override fun tokenLocationHint(): String = "Verify your PAT at: User settings > Personal access tokens."

    override fun notFoundGuidance(): String =
        "Azure DevOps returns 404 when the organization, project or repository is wrong or hidden from the PAT." +
            "\nVerify the names in the URL and that the PAT has the Code (Read) scope."

    private val configuredToken = MutableStateFlow<String?>(null)
    private val configuredBaseUrl = MutableStateFlow(DEFAULT_BASE_URL)
    private val configuredOrganization = MutableStateFlow<String?>(null)

    /** Owns the HTTP client (credential scoped to its host) that [commentClient] also sends through. */
    internal val providerClient = ReinitializableClient<AzureDevOpsProviderClient> { it.close() }

    /** Sends through [providerClient]'s HTTP client, so it is rebuilt with it; closing it leaves that client open. */
    internal val commentClient = ReinitializableClient<AzureDevOpsClient> { it.close() }
    private var logger: Logger = NoOpLogger

    override suspend fun initializeTyped(
        context: PluginContext,
        config: AzureDevOpsConfig
    ): Boolean {
        logger = context.logger
        val credentials = AzureDevOpsCredentials.resolve(context.pluginConfig, context::env)
        val token = credentials.token ?: configuredToken.value
        configuredToken.value = token
        val baseUrl = try {
            // private hosts are checked per request against the run's NetworkPolicy (ssrfRedirectValidationPlugin)
            UrlValidator.validateBaseUrl(
                config.url?.trimEnd('/') ?: DEFAULT_BASE_URL,
                allowLocalhost = context.allowLocalhost,
                allowPrivateNetwork = true,
            )
        } catch (e: IllegalArgumentException) {
            context.warn("Azure DevOps: invalid url '${config.url}': ${e.message}")
            return false
        }
        configuredBaseUrl.value = baseUrl
        configuredOrganization.value = config.organization
        val provider = providerClient.rebuild(listOf(baseUrl, token, context.allowLocalhost)) {
            AzureDevOpsProviderClient(
                token = token,
                allowLocalhost = context.allowLocalhost,
                logger = context.logger,
                baseUrl = baseUrl,
                engine = engine,
            )
        }
        commentClient.rebuild(provider) {
            AzureDevOpsClient(
                baseUrl = baseUrl,
                token = token,
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
        context.log("Azure devops platform service module initialized")
        return true
    }

    // ── Provider methods ──

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest {
        val (organization, project, repository) = parseProjectId(projectId)
        return fetchPullRequest(organization, project, repository, mrId)
    }

    override suspend fun listOpenMergeRequestIds(
        projectId: String,
        targetBranch: String,
        limit: Int,
    ): List<String> {
        val activeClient = providerClient.value ?: error("Azure DevOps not initialized")
        val (organization, project, repository) = parseProjectId(projectId)
        return activeClient.listActivePullRequestIds(organization, project, repository, targetBranch, limit)
    }

    override fun invalidMergeRequestUrl(url: String): Nothing = throw IllegalArgumentException("Invalid Azure DevOps PR URL: $url")

    override suspend fun rateLimitStatus(): RateLimitInfo? = providerClient.value?.lastRateLimit() ?: commentClient.value?.lastRateLimit()

    override val supportsFileContent: Boolean = true

    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? {
        val (organization, project, repository) = parseProjectId(projectId)
        return withInitializedClient(providerClient.value, PLATFORM_NAME) {
            it.fetchFileContent(organization, project, repository, ref, path)
        }
    }

    @Suppress("TooGenericExceptionCaught") // boundary: blame is best-effort, failure is logged
    override suspend fun fetchFileBlame(
        projectId: String,
        filePath: String,
        ref: String
    ): List<BlameEntry> {
        val activeClient = providerClient.value ?: return emptyList()
        return bestEffort("Azure DevOps fetchFileBlame failed for $filePath@$ref", emptyList(), logger::warn) {
            val (organization, project, repository) = parseProjectId(projectId)
            AzureDevOpsAdapter.toBlameEntries(activeClient.fetchFileCommits(organization, project, repository, filePath, ref))
        }
    }

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? = azureCiEnvironment(id, env)

    override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? {
        val (baseUrl, match) = splitPrUrl(url) ?: return null
        val organization = match.groupValues[1]
        val project = match.groupValues[2]
        val repository = match.groupValues[3]
        val prId = match.groupValues[4]
        return ParsedMergeRequestUrl(
            platform = id,
            baseUrl = baseUrl,
            projectId = "$organization/$project/$repository",
            mrId = prId,
        )
    }

    override suspend fun parseGitRemoteUrl(url: String): String? {
        val sshMatch = GIT_SSH_REMOTE_REGEX.find(url)
        if (sshMatch != null) return "${sshMatch.groupValues[1]}/${sshMatch.groupValues[2]}/${sshMatch.groupValues[3]}"
        val httpsMatch = GIT_HTTPS_REMOTE_REGEX.find(url)
        if (httpsMatch != null) return "${httpsMatch.groupValues[1]}/${httpsMatch.groupValues[2]}/${httpsMatch.groupValues[3]}"
        return null
    }

    // ── Comment methods ──

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String
    ): String =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
            val (org, project, repoId) = parseProjectId(projectId)
            val thread = azureClient.postComment(org, project, repoId, requireMrIdAsInt(mrId), body)
            val threadId = thread.id ?: error("Thread ID not returned from Azure DevOps")
            val commentId = thread.comments.firstOrNull()?.id ?: error("Comment ID not returned from Azure DevOps")
            // Same 'threadId:commentId' form updateComment, deleteComment and findAllMatchingComments use.
            "$threadId:$commentId"
        }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
        val (org, project, repoId) = parseProjectId(projectId)
        val parts = commentId.split(":")
        require(parts.size == 2) { "Azure DevOps commentId must be in 'threadId:commentId' format, got: $commentId" }
        val threadId = parts[0].toInt()
        val actualCommentId = parts[1].toInt()
        azureClient.updateThread(org, project, repoId, requireMrIdAsInt(mrId), threadId, actualCommentId, body)
    }

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String
    ): String? = findAllMatchingComments(projectId, mrId, marker).firstOrNull()

    /** `threadId:commentId` of every thread whose first comment carries [marker]. */
    override suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
            val (org, project, repoId) = parseProjectId(projectId)
            azureClient.listThreads(org, project, repoId, requireMrIdAsInt(mrId)).value.mapNotNull { thread ->
                val first = thread.comments.firstOrNull()?.takeIf { AdapterHelpers.isSummaryComment(it.content, marker) }
                if (thread.id == null || first?.id == null) null else "${thread.id}:${first.id}"
            }
        }

    override suspend fun listMarkedComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
            val (org, project, repoId) = parseProjectId(projectId)
            // Threads and their comments come oldest first.
            azureClient
                .listThreads(org, project, repoId, requireMrIdAsInt(mrId))
                .value
                .flatMap { thread -> thread.comments.filter { it.commentType != "system" }.mapNotNull { it.content } }
                .filter { marker in it }
                .reversed()
        }

    override suspend fun deleteComment(
        projectId: String,
        mrId: String,
        commentId: String,
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
        val (org, project, repoId) = parseProjectId(projectId)
        val (threadId, id) = commentId.split(":").map { it.toInt() }
        azureClient.deleteComment(org, project, repoId, requireMrIdAsInt(mrId), threadId, id)
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
        withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
            val (org, project, repoId) = parseProjectId(projectId)
            val thread = azureClient.createInlineCommentThread(
                org,
                project,
                repoId,
                requireMrIdAsInt(mrId),
                body,
                filePath,
                line,
                startLine
            )
            thread.id?.toString() ?: error("Thread ID not returned from Azure DevOps inline comment")
        }

    override suspend fun resolveMarked(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int = resolveMarkedThreads(projectId, mrId, marker, filePaths)

    /** Sets thread [threadId] (or the thread of a `thread:comment` id) to `fixed`, replying [note] first when given. */
    override suspend fun resolveThread(
        projectId: String,
        mrId: String,
        threadId: String,
        note: String?,
    ): Result<Unit> =
        onThread(projectId, mrId, threadId) { azureClient, (org, project, repoId), pr, id ->
            note?.let { azureClient.replyToThread(org, project, repoId, pr, id, it) }
            azureClient.updateThreadStatus(org, project, repoId, pr, id, "fixed")
        }

    override suspend fun listReviewThreads(
        projectId: String,
        mrId: String,
    ): List<ReviewThread> =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
            val (org, project, repoId) = parseProjectId(projectId)
            azureClient.listThreads(org, project, repoId, requireMrIdAsInt(mrId)).value.mapNotNull { it.toReviewThread() }
        }

    override suspend fun replyToThread(
        projectId: String,
        mrId: String,
        threadId: String,
        body: String,
    ): Result<Unit> =
        onThread(projectId, mrId, threadId) { azureClient, (org, project, repoId), pr, id ->
            azureClient.replyToThread(org, project, repoId, pr, id, body)
        }

    override suspend fun updateNote(
        projectId: String,
        mrId: String,
        threadId: String,
        noteId: String,
        body: String,
    ): Result<Unit> =
        onThread(projectId, mrId, threadId) { azureClient, (org, project, repoId), pr, id ->
            val comment = requireNotNull(noteId.toIntOrNull()) { "Azure DevOps comment id must be a number: $noteId" }
            azureClient.updateThread(org, project, repoId, pr, id, comment, body)
        }

    private suspend fun onThread(
        projectId: String,
        mrId: String,
        threadId: String,
        block: suspend (AzureDevOpsClient, Triple<String, String, String>, Int, Int) -> Unit,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
                block(azureClient, parseProjectId(projectId), requireMrIdAsInt(mrId), azureThreadId(threadId))
            }
        }

    /** Sets this module's still-active inline threads to `fixed`; threads a reviewer already settled are left alone. */
    private suspend fun resolveMarkedThreads(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int =
        withInitializedClient(commentClient.value, PLATFORM_NAME) {
            it.fixMarkedThreads(parseProjectId(projectId), requireMrIdAsInt(mrId), marker, filePaths)
        }

    /** First comments of marked inline threads a reviewer set to "won't fix" or "by design" (the service only closes). */
    override suspend fun listDismissedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> =
        withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
            val (org, project, repoId) = parseProjectId(projectId)
            azureClient
                .listThreads(org, project, repoId, requireMrIdAsInt(mrId))
                .value
                .filter { it.threadContext != null && it.status in DISMISSED_THREAD_STATUSES }
                .mapNotNull { thread ->
                    thread.comments
                        .firstOrNull()
                        ?.content
                        ?.takeIf { it.contains(marker) }
                }
        }

    // ── Mutator methods ──

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
        val (organization, project, repositoryId) = parseProjectId(projectId)
        val pullRequestId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        for (label in labels) {
            azureClient.addLabel(organization, project, repositoryId, pullRequestId, label)
        }
    }

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
        val (organization, project, repositoryId) = parseProjectId(projectId)
        val pullRequestId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        val pr = azureClient.fetchPullRequest(organization, project, repositoryId, pullRequestId)
        val labelsToRemove = pr.labels.filter { it.name in labels }
        for (label in labelsToRemove) {
            azureClient.removeLabel(organization, project, repositoryId, pullRequestId, label.id)
        }
    }

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
        val (organization, project, repositoryId) = parseProjectId(projectId)
        val pullRequestId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        azureClient.updatePullRequest(organization, project, repositoryId, pullRequestId, buildJsonObject { put("isDraft", draft) })
    }

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
        val (organization, project, repositoryId) = parseProjectId(projectId)
        val pullRequestId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        azureClient.updatePullRequest(organization, project, repositoryId, pullRequestId, buildJsonObject { put("title", title) })
    }

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String
    ) = withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
        val (organization, project, repositoryId) = parseProjectId(projectId)
        val pullRequestId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        azureClient.updatePullRequest(
            organization,
            project,
            repositoryId,
            pullRequestId,
            buildJsonObject { put("description", description) },
        )
    }

    override suspend fun approve(
        projectId: String,
        mrId: String,
    ) = onPullRequest(projectId, mrId) { repo, pr -> setOwnApproval(repo, pr, approved = true, logger) }

    override suspend fun unapprove(
        projectId: String,
        mrId: String,
    ) = onPullRequest(projectId, mrId) { repo, pr -> setOwnApproval(repo, pr, approved = false, logger) }

    override suspend fun addReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = suspendCatching { onPullRequest(projectId, mrId) { repo, pr -> updateReviewers(repo, pr, usernames, add = true) } }

    override suspend fun removeReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = suspendCatching { onPullRequest(projectId, mrId) { repo, pr -> updateReviewers(repo, pr, usernames, add = false) } }

    /** Runs [block] on the initialized client with the pull request's `organization/project/repository` and number. */
    private suspend fun <T> onPullRequest(
        projectId: String,
        mrId: String,
        block: suspend AzureDevOpsClient.(Triple<String, String, String>, Int) -> T,
    ): T =
        withInitializedClient(commentClient.value, PLATFORM_NAME) {
            it.block(parseProjectId(projectId), requireMrIdAsInt(mrId, MR_ID_LABEL))
        }

    /** Unknown (the SDK default): project membership is behind the Graph API (a separate vssps host and scope) that the PAT used here does not cover. */
    override suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean? {
        logger.debug("Azure DevOps: project membership cannot be determined, treating '$username' in '$projectId' as unknown")
        return null
    }

    /** Null without a configured `organization`: the account is per organization. */
    override suspend fun currentUsername(): String? =
        configuredOrganization.value?.let { org ->
            withInitializedClient(providerClient.value, PLATFORM_NAME) { it.fetchCurrentUsername(org) }
        }

    override suspend fun fetchApprovalSignals(
        projectId: String,
        mrId: String,
    ): List<ApprovalSignal> =
        onPullRequest(projectId, mrId) { (org, project, repo), pr -> approvalSignals(listThreads(org, project, repo, pr).value) }

    override suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit> {
        val (org, project, repo) = parseProjectId(projectId)
        val creatorId = author?.takeIf { it.isNotBlank() }?.let { name ->
            withInitializedClient(commentClient.value, PLATFORM_NAME) { it.findIdentityIds(org, name) }.firstOrNull() ?: return emptyList()
        }
        return withInitializedClient(providerClient.value, PLATFORM_NAME) { client ->
            client.searchPullRequests(org, project, repo, creatorId, text, limit).map { it.toSearchHit("${client.baseUrl}/$org/$project") }
        }
    }

    override suspend fun checkAuth(): Boolean? =
        configuredOrganization.value?.let { org ->
            withInitializedClient(commentClient.value, PLATFORM_NAME) { it.checkAuth("${it.baseUrl}/$org/_apis/connectionData") }
        }

    /** Azure Repos PR comments apply a ```suggestion block in one click ("Suggest change"). */
    override suspend fun renderSuggestionBlock(suggestedCode: String): String = AdapterHelpers.renderSuggestion(suggestedCode)

    override suspend fun supports(operation: PlatformOperation): Boolean = operation in SUPPORTED

    /** A pull request status: the verdict and title only, no annotations. */
    override suspend fun publishResultStatus(
        projectId: String,
        mrId: String,
        status: ResultStatus,
    ) {
        val (organization, project, repository) = parseProjectId(projectId)
        val prId = requireMrIdAsInt(mrId, MR_ID_LABEL)
        withInitializedClient(
            commentClient.value,
            PLATFORM_NAME
        ) { it.publishPullRequestStatus(organization, project, repository, prId, status) }
    }

    override suspend fun mergeMr(
        projectId: String,
        mrId: String,
        options: MergeOptions,
    ): Result<Unit> = suspendCatching { onPullRequest(projectId, mrId) { repo, pr -> completePullRequest(repo, pr, options) } }

    override suspend fun closeMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = setStatus(projectId, mrId, "abandoned")

    override suspend fun reopenMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = setStatus(projectId, mrId, "active")

    // ── Private helpers ──

    private suspend fun setStatus(
        projectId: String,
        mrId: String,
        status: String,
    ): Result<Unit> =
        suspendCatching {
            withInitializedClient(commentClient.value, PLATFORM_NAME) { azureClient ->
                val (organization, project, repositoryId) = parseProjectId(projectId)
                val body = buildJsonObject { put("status", status) }
                azureClient.updatePullRequest(organization, project, repositoryId, requireMrIdAsInt(mrId, MR_ID_LABEL), body)
            }
        }

    /** `organization/project/repository`; a `project/repository` ID takes the configured `organization`. */
    private fun parseProjectId(projectId: String): Triple<String, String, String> {
        val organization = configuredOrganization.value
        val full = if (organization != null && projectId.count { it == '/' } == 1) "$organization/$projectId" else projectId
        return ProjectIdParser.parseTriple(full, PROJECT_ID_FORMAT)
    }

    /** The base URL and org/project/repo/id groups of a PR URL on the configured server or dev.azure.com. */
    private fun splitPrUrl(url: String): Pair<String, MatchResult>? {
        val base = configuredBaseUrl.value
        if (url.startsWith("$base/")) return PR_PATH_REGEX.find(url.removePrefix("$base/"))?.let { base to it }
        return PARSE_MR_URL_REGEX.find(url)?.let { DEFAULT_BASE_URL to it }
    }

    private suspend fun fetchPullRequest(
        organization: String,
        project: String,
        repository: String,
        prId: String,
    ): MergeRequest =
        (
            providerClient.value ?: error(
                "Azure DevOps not initialized"
            )
        ).fetchFullPullRequest(organization, project, repository, prId, logger)
}

/** Completes the pull request at its current source commit with [options]. */
private suspend fun AzureDevOpsClient.completePullRequest(
    repo: Triple<String, String, String>,
    pullRequestId: Int,
    options: MergeOptions,
) {
    val (organization, project, repositoryId) = repo
    val pr = fetchPullRequest(organization, project, repositoryId, pullRequestId)
    val head = checkNotNull(pr.lastMergeSourceCommit) { "Azure DevOps PR $pullRequestId has no source commit to complete" }
    val body = buildJsonObject {
        put("status", "completed")
        putJsonObject("lastMergeSourceCommit") { put("commitId", head.commitId) }
        putJsonObject("completionOptions") {
            put("squashMerge", options.squash)
            put("deleteSourceBranch", options.deleteSourceBranch)
            options.commitMessage?.let { put("mergeCommitMessage", it) }
        }
    }
    updatePullRequest(organization, project, repositoryId, pullRequestId, body)
}

private val OPEN_THREAD_STATUSES = setOf(null, "active", "pending", "unknown")

/** Sets the open inline threads whose first comment has [marker] (on [filePaths] when given) to `fixed`; returns how many. */
private suspend fun AzureDevOpsClient.fixMarkedThreads(
    repo: Triple<String, String, String>,
    pullRequestId: Int,
    marker: String,
    filePaths: Set<String>?,
): Int {
    val (org, project, repoId) = repo
    val open = listThreads(org, project, repoId, pullRequestId).value.filter { thread ->
        val path = thread.threadContext?.filePath?.removePrefix("/") ?: return@filter false
        thread.status in OPEN_THREAD_STATUSES &&
            thread.comments
                .firstOrNull()
                ?.content
                ?.contains(marker) == true &&
            (filePaths == null || path in filePaths)
    }
    return open.mapNotNull { it.id }.onEach { updateThreadStatus(org, project, repoId, pullRequestId, it, "fixed") }.size
}

/** `https://dev.azure.com/acme` and `https://server/tfs/acme` give `acme`; `https://acme.visualstudio.com` too. */
private fun organizationOf(collectionUri: String): String? {
    val withoutScheme = collectionUri.substringAfter("://")
    val host = withoutScheme.substringBefore('/')
    val path = withoutScheme.substringAfter('/', "").trim('/')
    return when {
        path.isNotEmpty() -> path.substringAfterLast('/')
        host.endsWith(".visualstudio.com") -> host.substringBefore('.')
        else -> null
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
    PlatformOperation.DISMISSED_INLINE,
    PlatformOperation.REVIEW_THREADS,
    PlatformOperation.INLINE_RANGES,
    PlatformOperation.FILE_CONTENT,
    PlatformOperation.MANAGE_REVIEWERS,
    PlatformOperation.SUGGESTIONS,
    PlatformOperation.RESULT_STATUS,
    PlatformOperation.APPROVAL_SIGNALS,
    PlatformOperation.SEARCH,
)

private fun azureCiEnvironment(
    platformId: String,
    env: (String) -> String?,
): CIEnvironment? {
    val isAzure = env("SYSTEM_TEAMFOUNDATIONSERVERURI") != null ||
        env("AZURE_HTTP_USER_AGENT") != null ||
        env("TF_BUILD") != null
    if (!isAzure) return null
    val collectionUri = env("SYSTEM_TEAMFOUNDATIONCOLLECTIONURI")?.trimEnd('/')
    val teamProject = env("SYSTEM_TEAMPROJECT")
    val baseUrl = when {
        collectionUri != null && teamProject != null -> "$collectionUri/$teamProject/_apis"
        collectionUri != null -> collectionUri
        else -> env("SYSTEM_TEAMFOUNDATIONSERVERURI")?.trimEnd('/')
    }
    val repository = env("BUILD_REPOSITORY_NAME")
    val organization = collectionUri?.let(::organizationOf)
    // the service parses `organization/project/repository`; a bare repository name cannot be parsed.
    val projectId = if (organization != null && teamProject != null && repository != null) {
        "$organization/$teamProject/$repository"
    } else {
        repository
    }
    val mrId = env("SYSTEM_PULLREQUEST_PULLREQUESTID")
    val token = env("SYSTEM_ACCESSTOKEN")
    return CIEnvironment(platform = platformId, projectId = projectId, mrId = mrId, token = token, baseUrl = baseUrl)
}

/** LSP hover text for this module's config block. */
private val HOVER_DOCUMENTATION = """
    # azureDevOps

    **Azure DevOps Provider**

    Fetches pull request data from Azure DevOps.

    ## Properties
    - `organization: String` - Azure DevOps organization
    - `project: String` - Project name
    - `token: String` - Personal access token
""".trimIndent()
