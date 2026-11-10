@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.localgit

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.FetchOptions
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.platform.GitRunner
import io.karandash.providers.common.UnifiedDiff
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Local git provider that constructs a [MergeRequest] from the local git repository.
 *
 * This provider does not require any remote API access. It uses `git` CLI commands
 * to gather branch, commit, and diff information from the local working copy.
 *
 * Useful for:
 * - Pre-push validation (validate before pushing to remote)
 * - Offline validation (no network required)
 * - Development workflow (quick feedback loop)
 *
 * Detection: returns a low confidence score (0.1) since local git is always available
 * as a fallback, but should not override platform-specific providers.
 */
class LocalGitProvider internal constructor(
    private val git: GitRunner,
) : PlatformServiceModule {
    constructor() : this(GitRunner.Default)

    override val id: String = ID
    override val name: String = "Local Git"
    override val version: String = "1.7.0"
    override val dslName: String = "localGit"
    override val defaultEnabled: Boolean = false
    override val defaultPriority: Int = 999
    override val supportsFileContent: Boolean = true

    override suspend fun supports(operation: PlatformOperation): Boolean = operation == PlatformOperation.FILE_CONTENT

    override val description: String = "Constructs MR data from local git repository without remote API access."

    private var logger: Logger = NoOpLogger

    companion object {
        const val ID = "local-git"
        private const val GIT_DETECTED_SCORE = 0.2
        private const val NUL = '\u0000'
        private const val BINARY_STAT = "-"
        private const val BINARY_DIFF = "Binary files differ"
        private const val NUMSTAT_FIELDS = 3
        private const val PATH_FORBIDDEN_START = "/:-"
    }

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        return true
    }

    override suspend fun detectConfidence(env: (String) -> String?): ProviderDetectionScore? =
        withContext(Dispatchers.IO) {
            val gitAvailable = runGit(REV_PARSE, "--is-inside-work-tree") == "true"
            if (!gitAvailable) return@withContext null
            ProviderDetectionScore(
                providerId = ID,
                score = GIT_DETECTED_SCORE,
                source = "local-git",
            )
        }

    override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? = null

    /** A local working copy has no CI pipeline, merge-conflict check, approvals, reviewers, or labels. */
    override fun unavailableMergeRequestFields(): Set<MergeRequestField> =
        setOf(
            MergeRequestField.CI_STATUS,
            MergeRequestField.CI_JOBS,
            MergeRequestField.CONFLICTS,
            MergeRequestField.APPROVALS,
            MergeRequestField.REVIEWERS,
            MergeRequestField.LABELS,
        )

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
    ): MergeRequest = fetchMergeRequest(projectId, mrId, FetchOptions())

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = fetchMergeRequestByUrl(url, FetchOptions())

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
        options: FetchOptions,
    ): MergeRequest =
        withContext(Dispatchers.IO) {
            buildLocalMergeRequest(
                targetBranch = projectId.ifEmpty { defaultTargetBranch() },
                contextLines = options.contextLines,
            )
        }

    override suspend fun fetchMergeRequestByUrl(
        url: String,
        options: FetchOptions,
    ): MergeRequest =
        withContext(Dispatchers.IO) {
            buildLocalMergeRequest(targetBranch = defaultTargetBranch(), contextLines = options.contextLines)
        }

    /**
     * `git show ref:path` (path relative to the repository root), untrimmed; null when the path is missing at
     * [ref], the content is over [PlatformServiceModule.MAX_FILE_CONTENT_BYTES] (checked with `git cat-file -s`
     * before reading, logged at warn), the content is binary, or [ref]/[path] could be read as a git option or
     * escapes the tree (logged at warn).
     */
    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? =
        withContext(Dispatchers.IO) {
            if (!isSafeRef(ref) || !isSafePath(path)) {
                logger.warn("Skipping content of '$path' at '$ref': ref or path is not a plain tree location")
                return@withContext null
            }
            val cap = PlatformServiceModule.MAX_FILE_CONTENT_BYTES
            when (val blob = git.readBlob(ref, path, cap)) {
                is GitRunner.Blob.Text -> blob.content

                is GitRunner.Blob.TooLarge -> null.also {
                    logger.warn("Skipping content of $path at $ref: ${blob.bytes} bytes is over the $cap-byte cap")
                }

                GitRunner.Blob.Binary -> null.also { logger.debug("Skipping content of $path: binary") }

                is GitRunner.Blob.Failed -> null
            }
        }

    internal fun isSafeRef(ref: String): Boolean =
        ref.isNotEmpty() &&
            !ref.startsWith('-') &&
            ".." !in ref &&
            ref.none { it.isISOControl() || it.isWhitespace() || it == ':' }

    private fun isSafePath(path: String): Boolean =
        path.isNotEmpty() &&
            path[0] !in PATH_FORBIDDEN_START &&
            !path.startsWith("./") &&
            path.split('/').none { it == ".." }

    // ── Mutator methods (unsupported for local git) ──

    private fun unsupported(operation: String): Nothing = throw UnsupportedPlatformOperationException(operation)

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ): Unit = unsupported("addLabels")

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ): Unit = unsupported("removeLabels")

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean,
    ): Unit = unsupported("setDraft")

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String,
    ): Unit = unsupported("updateTitle")

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String,
    ): Unit = unsupported("updateDescription")

    override suspend fun approve(
        projectId: String,
        mrId: String,
    ): Unit = unsupported("approve")

    override suspend fun unapprove(
        projectId: String,
        mrId: String,
    ): Unit = unsupported("unapprove")

    // ── Comment-poster methods (unsupported for local git) ──

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String,
    ): String = unsupported("postComment")

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    ): Unit = unsupported("updateComment")

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? = null

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String = unsupported("postInlineComment")

    private fun buildLocalMergeRequest(
        targetBranch: String,
        contextLines: Int = FetchOptions.DEFAULT_CONTEXT_LINES,
    ): MergeRequest {
        val currentBranch = runGit(REV_PARSE, "--abbrev-ref", "HEAD") ?: "HEAD"
        val userName = runGit("config", "user.name") ?: "unknown"
        val userEmail = runGit("config", "user.email") ?: "unknown"

        val commitLog = runGit(
            "log",
            "--format=%H%n%s%n%an%n%ae%n%aI",
            "$targetBranch..HEAD",
        ) ?: ""

        val commits = parseCommitLog(commitLog)
        val title = commits.firstOrNull()?.message ?: currentBranch

        val changes = fetchFileChanges(targetBranch, contextLines)

        return MergeRequest(
            id = "local",
            iid = 0,
            title = title,
            description = null,
            author = User(username = userEmail, name = userName),
            sourceBranch = currentBranch,
            targetBranch = targetBranch,
            state = MergeRequestState.OPENED,
            draft = currentBranch.startsWith("draft/") || currentBranch.startsWith("wip/"),
            labels = emptyList(),
            changes = changes,
            commits = commits,
            approvals = emptyList(),
            reviewers = emptyList(),
        )
    }

    private fun parseCommitLog(log: String): List<Commit> {
        if (log.isBlank()) return emptyList()
        val lines = log.lines()
        val commits = mutableListOf<Commit>()
        var i = 0
        while (i + COMMIT_LOG_FIELDS - 1 < lines.size) {
            val sha = lines[i]
            val message = lines[i + 1]
            val authorName = lines[i + 2]
            val authorEmail = lines[i + 3]
            val timestamp = lines[i + 4]
            commits.add(
                Commit(
                    sha = sha,
                    message = message,
                    author = CommitAuthor(name = authorName, email = authorEmail),
                    timestamp = timestamp,
                ),
            )
            i += COMMIT_LOG_FIELDS
        }
        return commits
    }

    /**
     * Fetch file changes with real diff content: `git diff --name-status -z` (paths and change types),
     * `--numstat -z` (addition/deletion counts, `-` for binary files) and the unified diff split per file by
     * [UnifiedDiff.splitByFile]. NUL-separated output keeps paths with spaces, tabs or ` b/` intact.
     */
    private fun fetchFileChanges(
        targetBranch: String,
        contextLines: Int,
    ): List<FileChange> {
        val range = "$targetBranch...HEAD"
        val nameStatus = runGitRaw("diff", "--name-status", "-z", range) ?: return emptyList()
        val numstat = runGitRaw("diff", "--numstat", "-z", range) ?: return emptyList()
        val clampedContext = contextLines.coerceIn(
            FetchOptions.MIN_CONTEXT_LINES,
            FetchOptions.MAX_CONTEXT_LINES,
        )
        val rawDiff = runGitRaw(
            "-c",
            "core.quotePath=false",
            "diff",
            "-U$clampedContext",
            "--no-color",
            range,
        ) ?: return emptyList()
        return parseFileChanges(nameStatus, numstat, rawDiff)
    }

    /** Builds [FileChange]s from `--name-status -z`, `--numstat -z` and unified diff output. */
    internal fun parseFileChanges(
        nameStatus: String,
        numstat: String,
        rawDiff: String,
    ): List<FileChange> {
        val stats = parseNumstat(numstat)
        val hunks = UnifiedDiff.splitByFile(rawDiff)
        val fields = nameStatus.split(NUL)
        val changes = mutableListOf<FileChange>()
        var i = 0
        while (i < fields.size) {
            val status = fields[i].firstOrNull() ?: break
            val pathCount = if (status == 'R' || status == 'C') 2 else 1
            val path = fields.getOrNull(i + pathCount) ?: break
            i += pathCount + 1
            val stat = stats[path]
            changes += FileChange(
                path = path,
                changeType = changeType(status),
                additions = stat?.first?.toIntOrNull() ?: 0,
                deletions = stat?.second?.toIntOrNull() ?: 0,
                diffContent = if (stat?.first == BINARY_STAT) BINARY_DIFF else hunks[path],
            )
        }
        return changes
    }

    private fun changeType(status: Char): ChangeType =
        when (status) {
            'A', 'C' -> ChangeType.ADDED
            'D' -> ChangeType.DELETED
            'R' -> ChangeType.RENAMED
            else -> ChangeType.MODIFIED
        }

    /**
     * Parse `--numstat -z` records: `additions<TAB>deletions<TAB>path`, or for a rename an empty path followed by
     * the old and new path as separate fields. Binary files show `-` counts. Keyed by the (new) path.
     */
    private fun parseNumstat(output: String): Map<String, Pair<String, String>> {
        val result = mutableMapOf<String, Pair<String, String>>()
        val fields = output.split(NUL)
        var i = 0
        while (i < fields.size) {
            val parts = fields[i].split('\t', limit = NUMSTAT_FIELDS)
            i++
            if (parts.size < NUMSTAT_FIELDS) continue
            val path = if (parts[2].isEmpty()) fields.getOrNull(i + 1).also { i += 2 } else parts[2]
            if (path != null) result[path] = parts[0] to parts[1]
        }
        return result
    }

    /** The remote's default branch (`origin/HEAD`), else a local `main` or `master`. */
    private fun defaultTargetBranch(): String =
        pickDefaultBranch(runGit("symbolic-ref", "--short", "refs/remotes/origin/HEAD")) { branch ->
            runGit(REV_PARSE, "--verify", "--quiet", "refs/heads/$branch") != null
        }

    private fun runGit(vararg args: String): String? = runGitRaw(*args)?.trim()

    private fun runGitRaw(vararg args: String): String? {
        val result = git.runSync(args.toList()) ?: return null
        if (result.isSuccess) return result.output
        logger.debug("git ${args.joinToString(" ")} exited with ${result.exitCode}")
        return null
    }
}

/** the remote default branch when known, else `main` or `master` if present locally, else `main`. */
internal fun pickDefaultBranch(
    originHead: String?,
    hasBranch: (String) -> Boolean,
): String = originHead?.takeIf { it.isNotBlank() } ?: listOf("main", "master").firstOrNull(hasBranch) ?: "main"

/** Number of lines per commit entry in the git log format. */
private const val COMMIT_LOG_FIELDS = 5

private const val REV_PARSE = "rev-parse"
