@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.core

import io.karandash.api.finding.InlineFindingThread
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.run.MergeRequestThreads
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import io.karandash.plugins.ai.core.config.InlineMrOverrideParser
import io.karandash.plugins.ai.core.config.ThreadCommand

/**
 * Learns from replies to karandash inline threads. The state is re-derived from the MR's threads on every run, so
 * nothing about replies is persisted between runs.
 *
 * Only project members are heard. A member's `@karandash ignore|wontfix|resolve` at the start of a reply line
 * dismisses the thread's findings with that reason and resolves the thread, once; it outranks the keyword classifier,
 * and reopening the thread overrules it. `why` and `ask` are answered by [ThreadAnswers] instead. Any other
 * `@karandash <word>` gets one help reply. A reply without a command
 * goes through [FeedbackStore.recordReply].
 */
object ThreadFeedback {
    /** Marks the bot's help reply so a thread gets it once. */
    const val HELP_MARKER = InlineFindingThread.HELP_MARKER

    private val HELP =
        "$HELP_MARKER\nUnknown command. Reply with `@karandash ignore` (not a problem here), " +
            "`@karandash wontfix` (known, will not be fixed) or `@karandash resolve` (handled); " +
            "each resolves this thread and the finding is not raised again. " +
            "`@karandash why` explains how this finding came about; `@karandash ask <question>` asks about it. " +
            "One command per reply: only the first line that starts with a command counts."

    /**
     * Reads every karandash thread from [threads] into [store]. A platform that cannot list threads adds nothing. With
     * [dryRun] the store learns as usual but nothing is resolved or replied on the platform. A reply the keyword
     * classifier leaves unknown goes to [judge] when one is given (`ai.feedback.reply-judge`). A sha in a reply counts
     * as a fix commit only when it is one of the MR's [commits]; a non-bot reaction on the bot note that contradicts
     * a verdict, the judge's included, leaves the reply unknown.
     */
    suspend fun ingest(
        store: FeedbackStore,
        threads: MergeRequestThreads,
        logger: Logger,
        dryRun: Boolean = false,
        judge: ReplyJudge? = null,
        commits: Set<String>? = null,
    ) {
        val acting = if (dryRun) ReadOnlyThreads(threads, logger) else threads
        for ((thread, heard) in heardThreads(threads, logger)) apply(store, acting, thread, heard, logger, judge, commits)
    }

    /** Every karandash thread of [threads] with the replies of project members, for the threads that have any. */
    internal suspend fun heardThreads(
        threads: MergeRequestThreads,
        logger: Logger,
    ): List<Pair<InlineFindingThread, List<ReviewNote>>> {
        val members = mutableMapOf<String, Boolean>()
        val botThreads = InlineFindingThread.trustedOf(listThreads(threads, logger), botUsername(threads, logger)) { author ->
            members.getOrPut(author) { isMember(threads, author, logger) }
        }
        return botThreads
            .map { thread ->
                thread to thread.humanReplies.filter { reply ->
                    val author = reply.author
                    author != null && members.getOrPut(author) { isMember(threads, author, logger) }
                }
            }.filter { it.second.isNotEmpty() }
    }

    /** At most one platform action per thread and run: one resolve or one help reply. */
    private suspend fun apply(
        store: FeedbackStore,
        threads: MergeRequestThreads,
        thread: InlineFindingThread,
        replies: List<ReviewNote>,
        logger: Logger,
        judge: ReplyJudge?,
        commits: Set<String>?,
    ) {
        val commands = replies.mapNotNull { reply -> InlineMrOverrideParser.parseThreadCommand(reply.body)?.let { reply to it } }
        // why and ask are answered by ThreadAnswers: they neither dismiss the thread nor get the help reply
        val command = commands.lastOrNull { it.second != ThreadCommand.UNKNOWN && !it.second.answers }
        when {
            command != null -> {
                applyCommand(store, threads, thread, command.first, command.second, logger)
            }

            commands.any { it.second == ThreadCommand.UNKNOWN } && !thread.hasBotNote(HELP_MARKER) -> {
                threads.reply(thread.thread.id, HELP).onFailure { logger.warn("Failed to post command help: ${it.message}") }
            }
        }
        val pending = thread.refs.filterNot { store.isDismissed(it.rule, it.path, it.messageHash) }
        if (pending.isEmpty()) return
        replies
            .filter { reply -> commands.none { it.first.id == reply.id } }
            .forEach { reply ->
                val verdict = verdictOf(reply.body, thread, judge, commits)
                pending.forEach { store.recordReply(it, reply.body, verdict) }
            }
    }

    private suspend fun verdictOf(
        reply: String,
        thread: InlineFindingThread,
        judge: ReplyJudge?,
        commits: Set<String>?,
    ): ReplyVerdict {
        val bot = thread.botNote.author
        val reaction = ReplyReaction.of(
            thread.botNote.awards
                .filter { it.user == null || it.user != bot }
                .map { it.name }
        )
        val text = ReplyEvidence(reply, ReplyClassifier.fixCommitCited(reply, commits))
        val verdict = ReplyClassifier.classify(text.copy(reaction = reaction))
        // a text verdict the reaction vetoed stays unknown: the judge must not overrule the reaction either
        if (verdict != ReplyVerdict.UNKNOWN || judge == null || ReplyClassifier.classify(text) != ReplyVerdict.UNKNOWN) return verdict
        return ReplyClassifier.withReaction(judge.judge(reply, ReplyJudge.excerpt(thread.botNote.body)), reaction)
    }

    /**
     * Dismisses [thread]'s findings for [command] and resolves the thread with a note naming [reply]. Once that note
     * exists the command is spent: it keeps the findings dismissed while the thread stays resolved, and a person
     * reopening the thread overrules it.
     */
    private suspend fun applyCommand(
        store: FeedbackStore,
        threads: MergeRequestThreads,
        thread: InlineFindingThread,
        reply: ReviewNote,
        command: ThreadCommand,
        logger: Logger,
    ) {
        val marker = "${InlineFindingThread.RESOLVED_BY_MARKER_PREFIX}${reply.id} -->"
        val applied = thread.hasBotNote(marker)
        if (applied && !thread.thread.resolved) return
        val reason = command.name.lowercase()
        thread.refs
            .filterNot { store.isDismissed(it.rule, it.path, it.messageHash) }
            .forEach { store.dismiss(it, reason) }
        if (applied) return
        val by = "`@karandash $reason` from @${reply.author}."
        // an already resolved thread only gets the marker, so the command stays spent after a reopen
        val result = if (thread.thread.resolved) {
            threads.reply(thread.thread.id, "$marker\nRecorded $by")
        } else {
            threads.resolve(thread.thread.id, "$marker\nResolved by $by")
        }
        result.onFailure { logger.warn("Failed to record command on thread ${thread.thread.id}: ${it.message}") }
    }

    @Suppress("TooGenericExceptionCaught") // boundary: replies that cannot be listed are not learned from this run
    private suspend fun listThreads(
        threads: MergeRequestThreads,
        logger: Logger,
    ): List<ReviewThread> =
        try {
            threads.list().orEmpty()
        } catch (e: Exception) {
            // boundary: platform API call; no reply feedback this run
            e.rethrowCancellation()
            logger.warn("Could not list review threads, replies are not learned from this run: ${e.message}")
            emptyList()
        }

    @Suppress("TooGenericExceptionCaught") // boundary: an unknown bot identity falls back to project membership
    private suspend fun botUsername(
        threads: MergeRequestThreads,
        logger: Logger,
    ): String? =
        try {
            threads.botUsername()
        } catch (e: Exception) {
            // boundary: platform API call; marker notes are then trusted from project members
            e.rethrowCancellation()
            logger.warn("Could not read the bot's username, trusting inline markers from project members: ${e.message}")
            null
        }

    @Suppress("TooGenericExceptionCaught") // boundary: a failed membership check only means the reply is not heard
    private suspend fun isMember(
        threads: MergeRequestThreads,
        author: String,
        logger: Logger,
    ): Boolean =
        try {
            threads.isMember(author)
        } catch (e: Exception) {
            // boundary: platform API call; the author's replies are ignored this run
            e.rethrowCancellation()
            logger.warn("Could not check whether $author is a project member, ignoring their replies: ${e.message}")
            false
        }
}

/** [inner] that only reads: resolving and replying are logged as skipped. Used in a dry run. */
private class ReadOnlyThreads(
    private val inner: MergeRequestThreads,
    private val logger: Logger,
) : MergeRequestThreads by inner {
    override suspend fun resolve(
        threadId: String,
        note: String,
    ): Result<Unit> = Result.success(Unit).also { logger.info("Dry run: would resolve thread $threadId") }

    override suspend fun reply(
        threadId: String,
        body: String,
    ): Result<Unit> = Result.success(Unit).also { logger.info("Dry run: would reply to thread $threadId") }
}
