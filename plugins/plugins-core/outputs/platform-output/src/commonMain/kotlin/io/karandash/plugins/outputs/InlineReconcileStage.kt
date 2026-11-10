package io.karandash.plugins.outputs

import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.InlineFindingThread
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger

/**
 * Carries out [InlineReconciler]'s decisions on one merge request: brings an open thread's bot
 * note up to date when the finding's note now reads differently, resolves or answers fixed threads and hands the new
 * comments to the caller to post.
 *
 * @param render The note body the platform gets for a comment body (native suggestion blocks).
 * @param stamp Appends the `updated …` line to an edited note; left out when comparing notes.
 * @param language Language of the fixed notes (`report.language`); a note is recognised as fixed in any language.
 *
 * @lifecycle per-run -- built for one output call.
 */
internal class InlineReconcileStage(
    private val platform: PlatformServiceModule,
    private val project: String,
    private val mr: String,
    private val logger: Logger,
    private val onFixed: InlineOnFixed = InlineOnFixed.EDIT,
    private val render: suspend (String) -> String = { it },
    private val stamp: ((String) -> String)? = null,
    private val language: String? = null,
) {
    /**
     * Outcome of [run]: comments to post, the open and fixed counts for the summary delta, and the comments an open
     * thread already shows ([covered]).
     */
    class Outcome(
        val newComments: List<InlineCommentData>,
        val openCount: Int,
        val fixedCount: Int,
        val covered: List<InlineCommentData> = emptyList(),
    )

    /** Reconciles [comments] with the karandash threads in [threads] at head [headSha]. */
    suspend fun run(
        comments: List<InlineCommentData>,
        threads: List<InlineFindingThread>,
        headSha: String,
    ): Outcome {
        val match = InlineReconciler.match(comments, threads)
        val open = match.matched.filterValues { !it.thread.resolved }
        open.entries
            .groupBy({ it.value }, { it.key })
            .forEach { (thread, matched) -> matched.singleOrNull()?.let { refresh(thread, it) } }
        val candidates = InlineReconciler.candidates(match)
        val headContent = headContent(candidates.mapNotNull { it.path }.toSet(), headSha)
        val fixed = candidates.filter { InlineReconciler.isFixed(it, headContent) }
        val fixedCount = fixed.count { markFixed(it, headSha) }
        return Outcome(match.unmatchedFindings, InlineReconciler.openCount(match), fixedCount, open.keys.toList())
    }

    /**
     * Edits [thread]'s bot note to [comment]'s body when they differ (a reworded message, a new suggestion); the
     * `updated …` line is not part of the comparison, so an unchanged finding never edits its note. A note edited as
     * fixed is rewritten too: the finding is back in an open thread.
     */
    private suspend fun refresh(
        thread: InlineFindingThread,
        comment: InlineCommentData,
    ) {
        val note = thread.botNote
        val body = render(comment.body)
        if (normalized(UpdatedStamp.withoutStamp(note.body)) == normalized(body)) return
        platform
            .updateNote(project, mr, thread.thread.id, note.id, stamp?.invoke(body) ?: body)
            .onFailure { logger.warn("Could not update inline thread ${thread.thread.id}: ${it.message}") }
    }

    private fun normalized(body: String): String = body.replace("\r\n", "\n").trim()

    /**
     * [InlineOnFixed.EDIT]: edit the bot's note to say it is fixed, and resolve the thread unless a person replied
     * (never resolve over a person). [InlineOnFixed.REPLY]: no human in the thread: resolve it with a note; otherwise
     * say once that it looks fixed.
     *
     * @return Whether the platform accepted the edit, resolve or reply.
     */
    private suspend fun markFixed(
        thread: InlineFindingThread,
        headSha: String,
    ): Boolean {
        val id = thread.thread.id
        val result = when {
            onFixed == InlineOnFixed.EDIT -> editFixed(thread, headSha)

            thread.humanReplies.isEmpty() -> platform.resolveThread(
                project,
                mr,
                id,
                "${InlineFindingThread.FIXED_MARKER}\n${InlineLifecycleText.FIXED.of(language, headSha)}",
            )

            InlineReconciler.hasLooksFixedReply(thread) -> Result.success(Unit)

            else -> platform.replyToThread(
                project,
                mr,
                id,
                "${InlineFindingThread.LOOKS_FIXED_MARKER}\n${InlineLifecycleText.LOOKS_FIXED.of(language, headSha)}",
            )
        }
        result.onFailure { logger.warn("Failed to mark inline thread $id fixed: ${it.message}") }
        return result.isSuccess
    }

    private suspend fun editFixed(
        thread: InlineFindingThread,
        headSha: String,
    ): Result<Unit> {
        val note = thread.botNote
        val edited =
            if (isFixedNote(note.body)) {
                Result.success(Unit)
            } else {
                fixedBody(note.body, headSha, language).let { body ->
                    platform.updateNote(project, mr, thread.thread.id, note.id, stamp?.invoke(body) ?: body)
                }
            }
        if (edited.isFailure || thread.humanReplies.isNotEmpty()) return edited
        return platform.resolveThread(project, mr, thread.thread.id)
    }

    @Suppress("TooGenericExceptionCaught") // boundary: a file the platform cannot serve only keeps its threads open
    private suspend fun headContent(
        paths: Set<String>,
        headSha: String,
    ): Map<String, String> {
        if (paths.isEmpty() || !platform.supportsFileContent) return emptyMap()
        // limit: one request per candidate file, sequential; batch or parallelize if MRs with many fixed threads get slow
        return paths
            .mapNotNull { path ->
                try {
                    platform.fetchFileContent(project, headSha, path)?.let { path to it }
                } catch (e: Exception) {
                    // boundary: platform API call; the file's threads stay open
                    e.rethrowCancellation()
                    logger.warn("Could not fetch $path at $headSha, its inline threads stay open: ${e.message}")
                    null
                }
            }.toMap()
    }

    companion object {
        /** Start of an English bot note edited as fixed before the note carried [InlineFindingThread.FIXED_MARKER]. */
        const val FIXED_PREFIX = "✅ Fixed in "

        /**
         * Whether [body] is a bot note edited as fixed, in any language: it carries [InlineFindingThread.FIXED_MARKER] or
         * starts with the English [FIXED_PREFIX] (notes edited before the marker). Marking fixed again leaves it, a
         * returning finding rewrites it.
         */
        fun isFixedNote(body: String): Boolean = body.startsWith(FIXED_PREFIX) || InlineFindingThread.FIXED_MARKER in body

        /**
         * [body] under the fixed line in [language] and [InlineFindingThread.FIXED_MARKER], its first visible line (the
         * header) struck through; markers stay.
         */
        fun fixedBody(
            body: String,
            headSha: String,
            language: String? = null,
        ): String {
            val lines = body.lines()
            val header = lines.indexOfFirst { it.isNotBlank() && !it.trimStart().startsWith("<!--") }
            val struck = lines.mapIndexed { i, line -> if (i == header) "~~${line.trim()}~~" else line }
            val fixedLine = InlineLifecycleText.FIXED_IN.of(language, headSha)
            return "$fixedLine\n${InlineFindingThread.FIXED_MARKER}\n\n${struck.joinToString("\n")}"
        }
    }
}
