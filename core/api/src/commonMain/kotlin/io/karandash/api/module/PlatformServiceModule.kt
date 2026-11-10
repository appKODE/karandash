package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.StableKarandashApi
import io.karandash.api.finding.FindingFeedback
import io.karandash.api.finding.ReviewThread
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.api.platform.RateLimitInfo
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.BlameEntry
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest

/**
 * Unified interface for platform services (GitLab, GitHub, Bitbucket, etc.).
 *
 * Combines MR fetching, mutation ([MergeRequestMutator]) and comment posting ([MergeRequestCommentPoster]) into a single module
 * per platform. Each platform implements one [PlatformServiceModule] that
 * handles all interactions with that platform's API.
 *
 * Each optional operation is either implemented or declared unsupported: [supports] answers per
 * [PlatformOperation], and an unsupported call throws [UnsupportedPlatformOperationException] (or returns it as
 * `Result.failure`), except query methods documented to return `null` when the service cannot tell.
 *
 * Configured via the `platforms:` config block:
 * ```yaml
 * platforms:
 *   gitlab:
 *     token: "${GITLAB_TOKEN}"
 *     url: "https://gitlab.com"
 * ```
 */
@StableKarandashApi
public interface PlatformServiceModule :
    Module,
    MergeRequestMutator,
    MergeRequestCommentPoster {
    override val type: ModuleType get() = ModuleType.PLATFORM_SERVICE

    public companion object {
        /** Largest file [fetchFileContent] returns; bigger files come back as `null`. */
        public const val MAX_FILE_CONTENT_BYTES: Int = 512 * 1024
    }

    // ── Provider methods ──

    /**
     * Default public API base URL. null for self-hosted-only providers (Gerrit, Gitea, AWS).
     */
    public suspend fun defaultApiUrl(): String? = null

    /**
     * Returns a hint for the environment variable(s) used for authentication.
     * Used by setup wizard and error messages to guide users on how to authenticate.
     */
    public suspend fun authTokenHint(): String? = null

    /**
     * Returns guidance about required token scopes/permissions for this provider.
     * Used by CLI error messages when a 403 (Forbidden) response is received.
     */
    public fun permissionScope(): String? = null

    /**
     * Returns guidance about where to find/verify the authentication token.
     * Used by CLI error messages when a 401 (Unauthorized) response is received.
     */
    public fun tokenLocationHint(): String? = null

    /**
     * Returns guidance about what a 404 response might mean for this provider.
     * Some providers (e.g. GitLab) return 404 instead of 403 for inaccessible resources.
     */
    public fun notFoundGuidance(): String? = null

    /**
     * Additional names by which this platform can be identified in auth blocks,
     * CLI `--platform` flags, and environment variable values.
     *
     * Aliases are matched case-insensitively with non-alphanumeric characters stripped
     * via [Module.normalizeId]. The platform's [id] and [dslName] are always implicitly
     * included and do not need to be listed here.
     */
    public suspend fun aliases(): Set<String> = emptySet()

    /**
     * Fetch a merge request by project ID and MR ID.
     */
    public suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
    ): MergeRequest

    /**
     * Fetch a merge request by its full URL.
     */
    public suspend fun fetchMergeRequestByUrl(url: String): MergeRequest

    /**
     * Fetch a merge request with options (context lines, etc.). Default impl ignores [options] and delegates to the
     * no-options variant. Services that want to honour [options] override this method.
     */
    public suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
        options: io.karandash.api.platform.FetchOptions,
    ): MergeRequest = fetchMergeRequest(projectId, mrId)

    /**
     * Fetch a merge request by URL with options. Default impl ignores [options] and delegates to the no-options
     * variant.
     */
    public suspend fun fetchMergeRequestByUrl(
        url: String,
        options: io.karandash.api.platform.FetchOptions,
    ): MergeRequest = fetchMergeRequestByUrl(url)

    /**
     * Attempt to detect this platform's CI environment from environment variables.
     * Return [CIEnvironment] if this platform's CI is active, null otherwise.
     */
    public suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? = null

    /**
     * Score-based detection: analyze the environment and return a confidence score
     * indicating how likely it is that this platform is in use.
     *
     * Default implementation delegates to [detectCiEnvironment] and returns a score of 0.9
     * if CI environment is detected, or null if not detected.
     */
    public suspend fun detectConfidence(env: (String) -> String?): ProviderDetectionScore? {
        val ci = detectCiEnvironment(env) ?: return null
        return ProviderDetectionScore(
            providerId = id,
            score = 0.9,
            source = "ci:$id",
            ciEnvironment = ci,
        )
    }

    /**
     * Parse a PR/MR web URL into structured components.
     * Return [ParsedMergeRequestUrl] if the URL belongs to this platform, null otherwise.
     */
    public suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? = null

    /**
     * Parse a git remote URL (HTTPS or SSH) and return the project ID string.
     * Return the project ID string if the remote URL belongs to this platform, null otherwise.
     */
    public suspend fun parseGitRemoteUrl(url: String): String? = null

    /**
     * Fetch blame/annotation data for a file at a specific ref.
     *
     * Returns a list of [BlameEntry] associating line ranges with the commits that
     * last modified them. Platforms without a native blame API may return an empty list.
     */
    public suspend fun fetchFileBlame(
        projectId: String,
        filePath: String,
        ref: String,
    ): List<BlameEntry> = emptyList()

    /**
     * Whether [fetchFileContent] can return content ([PlatformOperation.FILE_CONTENT]). Default false: the pipeline
     * skips the head-content fill without calling it.
     */
    public val supportsFileContent: Boolean get() = false

    /**
     * Content of [path] at [ref] (a commit sha or branch), or `null` when the file does not exist at [ref] or it is
     * larger than [MAX_FILE_CONTENT_BYTES] (logged at warn). API failures other than "not found" are thrown; a
     * platform without [PlatformOperation.FILE_CONTENT] throws [UnsupportedPlatformOperationException] (the default).
     */
    public suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? = throw UnsupportedPlatformOperationException("fetchFileContent")

    /**
     * Approval-like signals on a merge request that are not platform approvals: comments
     * ([io.karandash.models.ApprovalMethod.COMMENT_KEYWORD]) and reactions
     * ([io.karandash.models.ApprovalMethod.EMOJI_REACTION]). The pipeline asks only when the approval rule's
     * `methods` lists one of them, and keeps the signals whose text matches `commentKeywords` / `approvalEmojis`.
     * API failures are thrown; a platform without [PlatformOperation.APPROVAL_SIGNALS] throws
     * [UnsupportedPlatformOperationException] (the default).
     */
    public suspend fun fetchApprovalSignals(
        projectId: String,
        mrId: String,
    ): List<io.karandash.models.ApprovalSignal> = throw UnsupportedPlatformOperationException("fetchApprovalSignals")

    /**
     * Whether [username] is a member of project [projectId] (inherited membership included), or `null` when this
     * platform cannot tell (the default); callers treat `null` as not a member. API failures are thrown.
     */
    public suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean? = null

    /**
     * Username of the account this platform service posts as, or `null` when the platform cannot tell (the default).
     * Callers use it to tell the bot's own notes from copies pasted by others. API failures are thrown.
     */
    public suspend fun currentUsername(): String? = null

    /**
     * Reviewer reactions on the findings karandash posted to MR [mrId] of [projectId]: a thumbs-down by anyone but the
     * bot on a note carrying [io.karandash.api.finding.InlineFindingMarker] lines is one
     * [FindingFeedback.FALSE_POSITIVE] per finding in the note. The pipeline only counts them. API failures come back as
     * `Result.failure`; the default is an empty list (the platform reads no feedback).
     */
    public suspend fun fetchFindingFeedback(
        projectId: String,
        mrId: String,
    ): Result<List<FindingFeedback>> = Result.success(emptyList())

    /**
     * Merge-request fields this platform never fills (its API has no such data, or it is not fetched).
     * Rules that require one of them ([RuleModule.requiredMergeRequestFields]) are skipped with a warning
     * instead of passing silently on the empty value. Default: empty (every field is filled).
     */
    @ExperimentalKarandashApi
    public fun unavailableMergeRequestFields(): Set<MergeRequestField> = emptySet()

    // ── Capabilities & rate limiting ───────────────────────────

    /**
     * Whether this platform implements [operation]; ask before calling an optional operation instead of reading its
     * failure. Default: false. An unsupported call returns
     * [io.karandash.api.platform.UnsupportedPlatformOperationException].
     */
    public suspend fun supports(operation: PlatformOperation): Boolean = false

    /**
     * Returns current API rate limit information, if available.
     * Returns null when the platform does not expose rate limit data.
     */
    public suspend fun rateLimitStatus(): RateLimitInfo? = null

    /**
     * Checks the configured credentials against the platform API (e.g. `GET /user`); used by `doctor`.
     * Returns `true` when they are accepted, `false` when the platform rejects them (HTTP 401/403), and
     * `null` when this service cannot check (the default). Other API failures are thrown.
     */
    public suspend fun checkAuth(): Boolean? = null

    /**
     * Ids of the open merge requests in [projectId] that target [targetBranch], newest first, at most [limit]; each
     * id is one [fetchMergeRequest] accepts. `null` (a bridged guest without an answer) or
     * [UnsupportedPlatformOperationException] (the default, without [PlatformOperation.LIST_OPEN]) make callers fall
     * back (e.g. `ai.dependencies` uses its local snapshot file). API failures are thrown.
     */
    public suspend fun listOpenMergeRequestIds(
        projectId: String,
        targetBranch: String,
        limit: Int,
    ): List<String>? = throw UnsupportedPlatformOperationException("listOpenMergeRequestIds")

    /**
     * Merge requests of [projectId] in any state whose title or description matches [text] and/or whose author is
     * [author] (either may be null, not both), newest first, at most [limit]. `null` when this service cannot tell;
     * without [PlatformOperation.SEARCH] it throws [UnsupportedPlatformOperationException] (the default). API
     * failures are thrown.
     */
    @ExperimentalKarandashApi
    public suspend fun searchMergeRequests(
        projectId: String,
        text: String?,
        author: String?,
        limit: Int,
    ): List<MergeRequestSearchHit>? = throw UnsupportedPlatformOperationException("searchMergeRequests")
}

/** Merge request mutations of a [PlatformServiceModule]: labels, draft, title, description, approval, reviewers, merge state. */
@StableKarandashApi
public interface MergeRequestMutator {
    // ── Mutator methods ──

    /**
     * Add labels to a merge request.
     */
    public suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    )

    /**
     * Remove labels from a merge request.
     */
    public suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    )

    /**
     * Set the draft status of a merge request.
     */
    public suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean,
    )

    /**
     * Update the title of a merge request.
     */
    public suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String,
    )

    /**
     * Update the description (body) of a merge request.
     */
    public suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String,
    )

    /**
     * Approve a merge request. Implementations should be idempotent.
     */
    public suspend fun approve(
        projectId: String,
        mrId: String,
    )

    /**
     * Remove a previously granted approval. Implementations should be idempotent.
     */
    public suspend fun unapprove(
        projectId: String,
        mrId: String,
    )

    // ── Reviewer management ────────────────────────────────────

    /**
     * Adds reviewers to a merge request.
     * @return Result.success if added, Result.failure with [UnsupportedPlatformOperationException] if unsupported, or the platform error.
     */
    public suspend fun addReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = Result.failure(UnsupportedPlatformOperationException("addReviewers"))

    /**
     * Removes reviewers from a merge request.
     * @return Result.success if removed, Result.failure with [UnsupportedPlatformOperationException] if unsupported, or the platform error.
     */
    public suspend fun removeReviewers(
        projectId: String,
        mrId: String,
        usernames: List<String>,
    ): Result<Unit> = Result.failure(UnsupportedPlatformOperationException("removeReviewers"))

    // ── Merge control ──────────────────────────────────────────

    /**
     * Merges the merge request with the given options.
     * @return Result.failure with [UnsupportedPlatformOperationException] if not implemented.
     */
    public suspend fun mergeMr(
        projectId: String,
        mrId: String,
        options: MergeOptions = MergeOptions(),
    ): Result<Unit> = Result.failure(UnsupportedPlatformOperationException("mergeMr"))

    /**
     * Closes the merge request without merging.
     * @return Result.failure with [UnsupportedPlatformOperationException] if not implemented.
     */
    public suspend fun closeMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = Result.failure(UnsupportedPlatformOperationException("closeMr"))

    /**
     * Reopens a previously closed merge request.
     * @return Result.failure with [UnsupportedPlatformOperationException] if not implemented.
     */
    public suspend fun reopenMr(
        projectId: String,
        mrId: String,
    ): Result<Unit> = Result.failure(UnsupportedPlatformOperationException("reopenMr"))
}

/** Comment operations of a [PlatformServiceModule]: summary comments, inline comments and their cleanup. */
@StableKarandashApi
public interface MergeRequestCommentPoster {
    // ── Comment-poster methods ──

    /**
     * Post a new comment on a merge request.
     * @return Comment ID as string
     */
    public suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String,
    ): String

    /**
     * Update an existing comment.
     */
    public suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    )

    /**
     * Find existing comment containing a marker string.
     * @return Comment ID if found, null otherwise
     */
    public suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String,
    ): String?

    /**
     * Post an inline comment on a specific file and line within a merge request diff.
     *
     * @param projectId The project identifier (format varies by platform).
     * @param mrId The merge request / pull request identifier.
     * @param body The comment body (markdown).
     * @param filePath The file path relative to the repository root.
     * @param line The line number in the new version of the file.
     * @param diffRefs The base/head/start SHAs for positioning the comment on the diff.
     * @param startLine Optional start line for multi-line comments.
     * @return Comment ID as string.
     */
    public suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int? = null,
    ): String

    /**
     * Renders a code suggestion block in the platform's native format.
     *
     * Platforms with native "Apply suggestion" support (GitLab, GitHub, Gitea) override this
     * to emit their native syntax. Other platforms use the default markdown diff fallback.
     */
    public suspend fun renderSuggestionBlock(suggestedCode: String): String =
        buildString {
            appendLine("```diff")
            suggestedCode.lines().forEach { appendLine("+ $it") }
            append("```")
        }

    /**
     * Delete all inline comments on a merge request whose body contains [marker].
     *
     * The host does not call this: old inline comments are settled through [resolveMarkedInlineComments] (resolve
     * the thread, or mark the comment outdated in place where threads cannot be resolved).
     * @return Number of comments deleted.
     */
    public suspend fun deleteMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int = 0

    /**
     * Resolve all inline comment threads whose body contains [marker]. A platform that cannot resolve threads
     * should mark each comment superseded in place instead (keeping its text and marker) and count it; nothing
     * else cleans up old inline comments.
     * @return Number of discussions/threads resolved or marked superseded.
     */
    public suspend fun resolveMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int = 0

    /**
     * Resolve inline comment threads whose body contains [marker] and whose
     * file path matches one of [filePaths].
     *
     * Used for cache-aware comment management: on a partial cache hit, only
     * resolve comments on files that were re-reviewed, leaving comments on
     * unchanged files untouched.
     *
     * The default implementation falls back to [resolveMarkedInlineComments]
     * (resolves all matching comments regardless of file path). Platforms that
     * support position-aware filtering should override this method.
     *
     * @return Number of discussions/threads resolved.
     */
    public suspend fun resolveMarkedInlineCommentsForFiles(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>,
    ): Int = resolveMarkedInlineComments(projectId, mrId, marker)

    /**
     * Bodies of the inline comments containing [marker] that a reviewer dismissed: a thumbs-down reaction or, where the
     * platform records who resolved a thread, a thread resolved by someone other than this service's user. `null`
     * when this service cannot tell; without [PlatformOperation.DISMISSED_INLINE] it throws
     * [UnsupportedPlatformOperationException] (the default). API failures are thrown.
     */
    public suspend fun listDismissedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String>? = throw UnsupportedPlatformOperationException("listDismissedInlineComments")

    /**
     * Every thread on the merge request with all its notes, positions and per-note reactions, or `null` when this
     * service cannot tell. Without [PlatformOperation.REVIEW_THREADS] it throws [UnsupportedPlatformOperationException]
     * (the default). API failures are thrown.
     */
    public suspend fun listReviewThreads(
        projectId: String,
        mrId: String,
    ): List<ReviewThread>? = throw UnsupportedPlatformOperationException("listReviewThreads")

    /**
     * Resolve thread [threadId], then reply with [note] when it is given (a failed resolve posts no reply).
     * @return Result.failure with [UnsupportedPlatformOperationException] if not implemented.
     */
    public suspend fun resolveThread(
        projectId: String,
        mrId: String,
        threadId: String,
        note: String? = null,
    ): Result<Unit> = Result.failure(UnsupportedPlatformOperationException("resolveThread"))

    /**
     * Add a reply with [body] to thread [threadId] without resolving it.
     * @return Result.failure with [UnsupportedPlatformOperationException] if not implemented.
     */
    public suspend fun replyToThread(
        projectId: String,
        mrId: String,
        threadId: String,
        body: String,
    ): Result<Unit> = Result.failure(UnsupportedPlatformOperationException("replyToThread"))

    /**
     * Replace the body of note [noteId] in thread [threadId] in place.
     * @return Result.failure with [UnsupportedPlatformOperationException] if not implemented.
     */
    public suspend fun updateNote(
        projectId: String,
        mrId: String,
        threadId: String,
        noteId: String,
        body: String,
    ): Result<Unit> = Result.failure(UnsupportedPlatformOperationException("updateNote"))

    /**
     * Find all comments matching a marker string, so duplicate summaries can be removed with [deleteComment]. The
     * default returns at most the one id [findExistingComment] finds, which never yields a duplicate.
     * @return List of comment IDs containing the marker.
     */
    public suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> = listOfNotNull(findExistingComment(projectId, mrId, marker))

    /**
     * Body of an MR comment whose text contains [marker] anywhere, not only at the start (unlike
     * [findExistingComment]); on platforms where comments are immutable, the newest such comment. A run reads back
     * what the previous one left in its summary. The default takes the first of [listMarkedComments]; a platform
     * that lists none leaves the previous state unknown (null) and the caller posts as before.
     */
    public suspend fun findCommentBody(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? = listMarkedComments(projectId, mrId, marker).firstOrNull()

    /**
     * Bodies of the MR's comments whose text contains [marker] anywhere, newest first, platform system notes excluded.
     * A platform implements this one listing; callers read through [findCommentBody]. The default lists nothing,
     * for posters with no comment listing.
     */
    public suspend fun listMarkedComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> = emptyList()

    /**
     * Delete a single comment by its ID.
     */
    public suspend fun deleteComment(
        projectId: String,
        mrId: String,
        commentId: String,
    ) {}
}
