@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.outputs

import io.karandash.Version
import io.karandash.api.Module
import io.karandash.api.PluginContext
import io.karandash.api.ReporterConfig
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.InlineFindingThread
import io.karandash.api.finding.ReviewThread
import io.karandash.api.module.ModuleScope
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.InlineCommentBatchPoster
import io.karandash.api.platform.nullIfUnsupported
import io.karandash.api.support.rethrowCancellation
import io.karandash.helpers.output.NotificationState
import io.karandash.helpers.output.NotifyWhen
import io.karandash.helpers.output.OnError
import io.karandash.helpers.output.handle
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.DiffRefs
import io.karandash.sdk.typed.AbstractTypedOutputModule
import kotlinx.atomicfu.atomic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Config for [PlatformOutputModule].
 *
 * @property inlineComments Whether to post inline file-level comments.
 * @property commentMarker Override marker for summary comment deduplication. `null` (default) uses the
 *   format's marker; when set, it is prepended to the summary body so the next run finds the comment.
 * @property dryRun When true, logs what would be posted without making API calls.
 * @property inline Lifecycle of inline threads across runs ([InlineLifecycleConfig]).
 * @property language Output language of the inline-lifecycle summary lines (`en`, `ru`, `pl`; others render in `en`).
 *   Set from `report.language` by the pipeline.
 * @property onError `warn` (default) only logs a summary comment that could not be posted; `fail` also fails the output.
 *   Inline comments are best effort either way.
 * @property notifyWhen `when`: which runs update the summary comment ([NotifyWhen.CHANGED] by default: a new verdict or
 *   a new set of findings). An unchanged summary body is never re-sent.
 * @property updateOnTextChange `true` (default) also edits the summary already on the MR when only its text changed
 *   (a new layout, an inline thread opened or fixed); an edit notifies nobody. `false`: only the verdict and findings count.
 * @property notificationState This run's verdict and findings, set by the pipeline; null posts as before (every run).
 * @property previousNotificationState The state the MR's summary comment carries, read once by the pipeline; null makes
 *   this output read it itself.
 * @property comments Which MR comments are posted ([CommentMode.ALL] by default: the summary and the inline comments).
 */
@Serializable
data class PlatformOutputConfig(
    val inlineComments: Boolean = true,
    val commentMarker: String? = null,
    val dryRun: Boolean = false,
    val inline: InlineLifecycleConfig = InlineLifecycleConfig(),
    val language: String? = null,
    val onError: OnError = OnError.WARN,
    @SerialName("when")
    val notifyWhen: NotifyWhen = NotifyWhen.CHANGED,
    val notificationState: NotificationState? = null,
    val previousNotificationState: NotificationState? = null,
    val comments: CommentMode = CommentMode.ALL,
    val updateOnTextChange: Boolean = true,
) : ReporterConfig

/**
 * Output module that posts formatted reports to a platform as MR/PR comments.
 *
 * The pipeline injects a [PlatformServiceModule] reference via [configurePlatformService] before
 * calling [output].
 *
 * Only accepts the `"markdown"` format.
 *
 * [ModuleScope.PER_RUN]: each run posts through its own handle ([newHandle]), so overlapping
 * runs never post to each other's MR.
 *
 * @param clock The time of the `updated …` line on the summary and on inline notes (fixed in tests).
 * @lifecycle per-run -- platform service is set once per pipeline invocation.
 */
class PlatformOutputModule(
    private val clock: Clock = Clock.System,
) : AbstractTypedOutputModule<PlatformOutputConfig>(PlatformOutputConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Platform Output"
    override val version: String = Version.VERSION
    override val outputId: String = OUTPUT_ID
    override val description: String = "Posts formatted reports as MR/PR comments via platform API."
    override val isDefaultPlatform: Boolean = true
    override val requiresPlatformContext: Boolean = true
    override val acceptedFormats: Set<String> = setOf("markdown")
    override val preferredFormat: String = "markdown"
    override val scope: ModuleScope get() = ModuleScope.PER_RUN

    private var logger: Logger = NoOpLogger

    /** @lifecycle per-run -- injected by the pipeline before output() is called. */
    private val platformService = atomic<PlatformServiceModule?>(null)
    private val projectId = atomic<String?>(null)
    private val mrId = atomic<String?>(null)
    private val diffRefs = atomic<DiffRefs?>(null)

    /**
     * Per-file set of new-file line numbers present in the MR diff, used to skip
     * posting inline comments that point at lines the MR never actually added or
     * changed. Empty map disables validation.
     *
     * @lifecycle per-run - rebuilt from MergeRequest.changes on every pipeline invocation.
     */
    private val diffLineRanges = atomic<Map<String, Set<Int>>>(emptyMap())

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        return true
    }

    override suspend fun newHandle(context: PluginContext): Module = PlatformOutputModule(clock)

    /** Drops the posting target so a shared instance never posts to a previous run's MR. */
    override suspend fun shutdown() {
        platformService.value = null
        projectId.value = null
        mrId.value = null
        diffRefs.value = null
        diffLineRanges.value = emptyMap()
    }

    /**
     * Inject platform context for comment posting.
     *
     * Called by the pipeline after provider resolution but before [outputTyped].
     * This avoids coupling the module to a concrete platform service through
     * [PluginContext], which does not carry platform references.
     *
     * @param diffLineRanges Optional per-file set of valid new-file line numbers.
     * When supplied, inline comments referencing lines outside this set are
     * skipped with a `warn` log rather than posted - preventing the platform
     * from placing a comment on a line the LLM never actually saw. Pass an empty
     * map to disable this guard.
     */
    override fun configurePlatformContext(
        platformService: PlatformServiceModule,
        projectId: String,
        mrId: String,
        diffRefs: DiffRefs?,
        diffLineRanges: Map<String, Set<Int>>,
    ) = configurePlatformService(platformService, projectId, mrId, diffRefs, diffLineRanges)

    fun configurePlatformService(
        platformService: PlatformServiceModule,
        projectId: String,
        mrId: String,
        diffRefs: DiffRefs?,
        diffLineRanges: Map<String, Set<Int>> = emptyMap(),
    ) {
        this.platformService.value = platformService
        this.projectId.value = projectId
        this.mrId.value = mrId
        this.diffRefs.value = diffRefs
        this.diffLineRanges.value = diffLineRanges
    }

    override suspend fun outputTyped(
        report: FormattedReport,
        config: PlatformOutputConfig,
    ) {
        if (config.comments == CommentMode.NONE) {
            logger.info("Platform output: comments=none, no MR comments posted")
            return
        }
        post(report, config.copy(inlineComments = config.inlineComments && config.comments.inline))
    }

    private suspend fun post(
        report: FormattedReport,
        config: PlatformOutputConfig,
    ) {
        val platform = platformService.value
        val project = projectId.value
        val mr = mrId.value

        if (platform == null || project == null || mr == null) {
            logger.warn("Platform output skipped: no platform context configured")
            return
        }

        if (config.dryRun) {
            logger.info("Platform output dry-run: would post summary + ${report.inlineComments.size} inline comments")
            return
        }

        val stamp = UpdatedStamp(clock, config.language)
        val poster = SummaryCommentPoster(platform, project, mr, logger, stamp)
        val state = config.notificationState
        val previous = state?.let { config.previousNotificationState ?: poster.previousState() }
        val inline = reconcileInlineComments(platform, project, mr, report, config, stamp)
        val delta = inline?.delta
        val summary = if (delta.isNullOrEmpty()) {
            report
        } else {
            report.copy(body = SummaryRows.withoutInline(report.body, inline.covered, config.language))
        }
        val body = poster
            .bodyToPost(summary, config, previous, delta.orEmpty())
            ?.takeIf { config.comments.summary }
        // Not reconciled: an unchanged outcome keeps the earlier inline comments instead of resolving and reposting them
        val postedWithReview =
            delta == null && !(state?.sameOutcome(previous) ?: false) &&
                postInlineComments(platform, project, mr, report, config, body, stamp)
        if (body == null || postedWithReview) return
        config.onError.handle(logger, "Failed to post comment") {
            poster.post(body, checkNotNull(poster.marker(summary, config)) { "a summary body implies a marker" })
        }
    }

    /**
     * The reconcile lifecycle: keep threads that still match a finding, post only new findings (at most
     * [InlineLifecycleConfig.maxPerRun]) and close threads whose code is gone ([InlineReconcileStage]).
     *
     * A full cache hit reconciles too: its comments match their threads, and fixed threads still close.
     *
     * @return The summary delta (a skip notice when the threads could not be listed, `""` when reconciling failed) and
     *   the comments an inline thread shows this run (posted, or matched to an open thread), or `null` when this run
     *   posts every inline comment instead ([postInlineComments]): inline comments off, no diff refs, or a platform that cannot list threads.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: inline reconciliation is non-fatal
    private suspend fun reconcileInlineComments(
        platform: PlatformServiceModule,
        project: String,
        mr: String,
        report: FormattedReport,
        config: PlatformOutputConfig,
        stamp: UpdatedStamp,
    ): InlineSummary? {
        val refs = diffRefs.value
        if (refs == null || !config.inlineComments) return null
        // Without the threads every comment would look new: post none rather than repost them all
        val threads =
            listReviewThreads(platform, project, mr).getOrElse {
                return InlineSummary(InlineReconciler.listingFailedNotice(config.language), emptyList())
            } ?: return null
        val outcome = try {
            val bot = botUsername(platform)
            val render: suspend (String) -> String = { renderSuggestions(platform, it) }
            InlineReconcileStage(platform, project, mr, logger, config.inline.onFixed, render, stamp::inline, config.language).run(
                report.inlineComments,
                InlineFindingThread.trustedOf(threads, bot) { platform.isProjectMember(project, it) == true },
                refs.headSha,
            )
        } catch (e: Exception) {
            // boundary: platform API call; nothing is posted rather than reposting every comment
            e.rethrowCancellation()
            logger.warn("Failed to reconcile inline comments, none posted this run: ${e.message}")
            return InlineSummary("", emptyList())
        }
        val postable = InlineReconciler.bySeverity(outcome.newComments.filter { isLineInDiff(it.filePath, it.line) })
        val max = config.inline.maxPerRun.coerceAtLeast(0)
        val posted = postEach(platform, project, mr, refs, postable.take(max), config.inline.publish, stamp)
        val overflow = postable.drop(max)
        val section = InlineReconciler.summarySection(posted.size + outcome.openCount, outcome.fixedCount, overflow, config.language)
        return InlineSummary(section, outcome.covered + posted)
    }

    /** What reconciling adds to the summary: the [delta] section, and the [covered] comments whose rows it drops. */
    private class InlineSummary(
        val delta: String,
        val covered: List<InlineCommentData>,
    )

    /** [PlatformServiceModule.listReviewThreads], retried once; a failure only when both attempts throw. */
    private suspend fun listReviewThreads(
        platform: PlatformServiceModule,
        project: String,
        mr: String,
    ): Result<List<ReviewThread>?> {
        val first = tryListReviewThreads(platform, project, mr, attempt = 1)
        return if (first.isSuccess) first else tryListReviewThreads(platform, project, mr, attempt = 2)
    }

    @Suppress("TooGenericExceptionCaught") // boundary: a listing failure skips inline comments for this run
    private suspend fun tryListReviewThreads(
        platform: PlatformServiceModule,
        project: String,
        mr: String,
        attempt: Int,
    ): Result<List<ReviewThread>?> =
        try {
            Result.success(nullIfUnsupported { platform.listReviewThreads(project, mr) })
        } catch (e: Exception) {
            // boundary: platform API call; one retry, then inline comments are skipped
            e.rethrowCancellation()
            logger.warn("Failed to list review threads (attempt $attempt of 2): ${e.message}")
            Result.failure(e)
        }

    @Suppress("TooGenericExceptionCaught") // boundary: an unknown bot identity falls back to project membership
    private suspend fun botUsername(platform: PlatformServiceModule): String? =
        try {
            platform.currentUsername()
        } catch (e: Exception) {
            // boundary: platform API call; marker notes are then trusted from project members
            e.rethrowCancellation()
            logger.warn("Could not read the bot's username, trusting inline markers from project members: ${e.message}")
            null
        }

    /**
     * Without reconciliation: resolves earlier inline comments and posts the current ones. With [InlinePublish.BATCH] a
     * platform that can ([InlineCommentBatchPoster.postReviewWithSummary]) gets [summary] in the same review.
     *
     * @return Whether [summary] was posted with the review (the caller then posts no separate summary).
     */
    private suspend fun postInlineComments(
        platform: PlatformServiceModule,
        project: String,
        mr: String,
        report: FormattedReport,
        config: PlatformOutputConfig,
        summary: String?,
        stamp: UpdatedStamp,
    ): Boolean {
        val refs = diffRefs.value
        if (refs == null || report.inlineComments.isEmpty() || !config.inlineComments) return false

        val changedFiles = report.changedFiles
        // Full cache hit: skip all inline resolution and posting - comments are already on the MR
        if (changedFiles != null && changedFiles.isEmpty()) return false

        val inlineMarker = report.inlineMarker
        if (inlineMarker != null) cleanUpOldInlineComments(platform, project, mr, inlineMarker, changedFiles)

        // Filter inline comments to only post for changed files (partial cache hit)
        val commentsToPost = if (changedFiles != null) {
            report.inlineComments.filter { it.filePath in changedFiles }
        } else {
            report.inlineComments // no cache - post all
        }

        val postable = commentsToPost.filter { isLineInDiff(it.filePath, it.line) }
        val batch = (platform as? InlineCommentBatchPoster).takeIf { config.inline.publish == InlinePublish.BATCH }
        if (batch != null && summary != null && postable.isNotEmpty() &&
            postReviewWithSummary(platform, batch, project, mr, refs, postable.map { it.copy(body = stamp.inline(it.body)) }, summary)
        ) {
            return true
        }
        postEach(platform, project, mr, refs, postable, config.inline.publish, stamp)
        return false
    }

    /** [comments] and [summary] as one review; `false` (after a warning) when not posted, so the caller posts them apart. */
    @Suppress("TooGenericExceptionCaught") // boundary: a failed combined review falls back to separate posts
    private suspend fun postReviewWithSummary(
        platform: PlatformServiceModule,
        batch: InlineCommentBatchPoster,
        project: String,
        mr: String,
        refs: DiffRefs,
        comments: List<InlineCommentData>,
        summary: String,
    ): Boolean =
        try {
            batch.postReviewWithSummary(
                projectId = project,
                mrId = mr,
                comments = comments.map { it.copy(body = renderSuggestions(platform, it.body)) },
                diffRefs = refs,
                summary = summary,
            )
        } catch (e: Exception) {
            // boundary: platform API call; the comments and the summary are posted apart
            e.rethrowCancellation()
            logger.warn("Failed to post the inline comments and the summary as one review, posting them apart: ${e.message}")
            false
        }

    /** Posts [comments], each with an `updated …` line ([stamp]); returns the ones the platform accepted. */
    @Suppress("TooGenericExceptionCaught") // boundary: inline comment posting is non-fatal per comment
    private suspend fun postEach(
        platform: PlatformServiceModule,
        project: String,
        mr: String,
        refs: DiffRefs,
        comments: List<InlineCommentData>,
        publish: InlinePublish,
        stamp: UpdatedStamp,
    ): List<InlineCommentData> {
        val batch = (platform as? InlineCommentBatchPoster).takeIf { publish == InlinePublish.BATCH }
        val stamped = comments.map { it.copy(body = stamp.inline(it.body)) }
        if (batch != null && comments.size > 1 && postBatch(platform, batch, project, mr, refs, stamped)) {
            return comments
        }
        return comments.filterIndexed { i, inline ->
            try {
                platform.postInlineComment(
                    projectId = project,
                    mrId = mr,
                    body = renderSuggestions(platform, stamped[i].body),
                    filePath = inline.filePath,
                    line = inline.line,
                    diffRefs = refs,
                    startLine = inline.startLine,
                )
                true
            } catch (e: Exception) {
                // boundary: platform API call; failure is logged and reported
                e.rethrowCancellation()
                val msg = "Failed to post inline comment on ${inline.filePath}:${inline.line}: ${e.message}"
                logger.warn(msg)
                false
            }
        }
    }

    /** One review for all [comments]; `false` (after a warning) when it failed, so the caller posts them one by one. */
    @Suppress("TooGenericExceptionCaught") // boundary: a failed batch falls back to per-comment posting
    private suspend fun postBatch(
        platform: PlatformServiceModule,
        batch: InlineCommentBatchPoster,
        project: String,
        mr: String,
        refs: DiffRefs,
        comments: List<InlineCommentData>,
    ): Boolean =
        try {
            batch.postInlineComments(
                projectId = project,
                mrId = mr,
                comments = comments.map { it.copy(body = renderSuggestions(platform, it.body)) },
                diffRefs = refs,
            )
            true
        } catch (e: Exception) {
            // boundary: platform API call; the comments are retried one by one
            e.rethrowCancellation()
            logger.warn("Failed to post ${comments.size} inline comments as one review, posting them one by one: ${e.message}")
            false
        }

    /**
     * Returns true when [line] is a valid new-file line for [filePath], or when no
     * diff line ranges were configured for this run. A line outside the diff is logged and skipped.
     */
    private fun isLineInDiff(
        filePath: String,
        line: Int
    ): Boolean {
        if (diffLineRanges.value.isEmpty()) return true
        val validLines = diffLineRanges.value[filePath] ?: return true
        if (line in validLines) return true
        logger.warn(
            "Skipping inline comment: $filePath:$line is not a new-file " +
                "line in this MR's diff — the comment would land on an unrelated location",
        )
        return false
    }

    /**
     * Resolves this module's previous inline comments, only those on re-reviewed files on a partial cache hit. Each
     * platform settles them its own way (resolves the thread or marks the comment outdated in place); nothing is
     * deleted, so comments on cached files stay and are not re-posted.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: inline cleanup is non-fatal
    private suspend fun cleanUpOldInlineComments(
        platform: PlatformServiceModule,
        project: String,
        mr: String,
        inlineMarker: String,
        changedFiles: Set<String>?,
    ) {
        try {
            if (changedFiles != null) {
                // Partial cache: resolve only comments on re-reviewed files
                platform.resolveMarkedInlineCommentsForFiles(project, mr, inlineMarker, changedFiles)
            } else {
                platform.resolveMarkedInlineComments(project, mr, inlineMarker)
            }
        } catch (e: Exception) {
            // boundary: platform API call; failure is logged and reported
            e.rethrowCancellation()
            logger.warn("Failed to clean up previous inline comments: ${e.message}")
        }
    }

    /**
     * Replaces each suggestion region the format marked in [body] with the platform's native
     * rendering ([PlatformServiceModule.renderSuggestionBlock], e.g. a ```` ```suggestion ```` block).
     */
    private suspend fun renderSuggestions(
        platform: PlatformServiceModule,
        body: String,
    ): String {
        val start = body.indexOf(InlineCommentData.SUGGESTION_START)
        val end = body.indexOf(InlineCommentData.SUGGESTION_END, start)
        if (start < 0 || end < 0) return body
        val code = body
            .substring(start + InlineCommentData.SUGGESTION_START.length, end)
            .trim('\n')
            .lines()
            .drop(1) // opening ```diff fence
            .dropLast(1) // closing fence
            .joinToString("\n") { it.removePrefix("+ ") }
        val rest = body.substring(end + InlineCommentData.SUGGESTION_END.length)
        return body.substring(0, start) + platform.renderSuggestionBlock(code) + renderSuggestions(platform, rest)
    }

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.output.platform"

        /** Output identifier. */
        const val OUTPUT_ID = "platform"
    }
}
