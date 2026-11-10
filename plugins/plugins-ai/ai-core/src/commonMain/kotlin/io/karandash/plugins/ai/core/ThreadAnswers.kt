@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.core

import io.karandash.api.finding.InlineFindingThread
import io.karandash.api.finding.InlineThreadMatcher
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.run.MergeRequestThreads
import io.karandash.logger.Logger
import io.karandash.models.finding.Finding
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.config.InlineMrOverrideParser
import io.karandash.plugins.ai.core.config.ThreadCommand

/**
 * Answers `@karandash why` and `@karandash ask <question>` replies of project members, once each. The answer is a
 * reply in the thread that starts with a marker naming the answered reply, so the state is re-derived from the MR's
 * threads on every run and nothing is persisted.
 *
 * `why` is deterministic: the finding's provenance from this run. `ask` is one model call by [ThreadAsker]; without
 * an asker (`ai.thread-commands.ask` off) it gets one short "disabled" reply.
 *
 * A transient non-answer (the finding is not in this run, no AI provider is configured, or the ask could not be
 * answered) is posted once under a `karandash-pending` marker, which does not close the command: later runs retry it
 * silently and post only a real answer, under the answered marker. A failed model call is retried once; if that fails
 * too, one final reply closes the command under the answered marker. New asks come before pending retries within
 * [MAX_ASKS_PER_RUN].
 */
object ThreadAnswers {
    /** Model calls for `ask` per run; asks over it stay unanswered, without a marker, and are tried on the next run. */
    const val MAX_ASKS_PER_RUN = 3

    /**
     * Answers every unanswered `why` and `ask` in [threads] about the run's [findings]; the fixed texts are in
     * [language] (`report.language`, via `ai.responseLanguage`; null is English).
     */
    suspend fun answer(
        threads: MergeRequestThreads,
        findings: List<Finding>,
        provenance: ReviewProvenance?,
        asker: ThreadAsker?,
        logger: Logger,
        askEnabled: Boolean = asker != null,
        language: String? = null,
    ) {
        var asks = 0
        val heard = ThreadFeedback.heardThreads(threads, logger)
        if (heard.isEmpty()) return
        val matched = InlineThreadMatcher.match(findings, heard.associate { it.first.thread.id to it.first.refs }).matched
        val todo = buildList {
            for ((thread, replies) in heard) {
                val finding = matched.entries.firstOrNull { it.value == thread.thread.id }?.key
                for (reply in replies) {
                    val command = InlineMrOverrideParser.parseThreadCommand(reply.body)?.takeIf { it.answers } ?: continue
                    if (thread.hasBotNote(answeredMarker(reply))) continue
                    add(
                        Todo(
                            thread,
                            reply,
                            command,
                            finding,
                            thread.hasBotNote(pendingMarker(reply))
                        )
                    )
                }
            }
        }
        // new asks come before pending retries, so a failing ask cannot starve the per-run cap
        for (item in todo.sortedBy { it.retry }) {
            val (thread, reply, command, finding, retry) = item
            val usesModel =
                command == ThreadCommand.ASK && finding != null && asker != null &&
                    InlineMrOverrideParser.askQuestion(reply.body) != null
            if (usesModel && asks >= MAX_ASKS_PER_RUN) continue
            if (usesModel) asks++
            val outcome = outcomeFor(command, reply, finding, provenance, asker, askEnabled, language)
            val (marker, text) = when {
                outcome.state == State.FINAL -> answeredMarker(reply) to outcome.text

                // a transient non-answer is posted once; later runs retry silently and post only a real answer
                !retry -> pendingMarker(reply) to outcome.text

                // a failed model call is retried once, then the command is closed
                outcome.state == State.FAILED -> answeredMarker(reply) to ThreadAnswerText.ASK_GAVE_UP.of(language)

                else -> continue
            }
            threads
                .reply(thread.thread.id, "$marker\n$text")
                .onFailure { logger.warn("Failed to answer ${command.name.lowercase()} on thread ${thread.thread.id}: ${it.message}") }
        }
    }

    private fun answeredMarker(reply: ReviewNote) = "${InlineFindingThread.ANSWERED_MARKER_PREFIX}${reply.id} -->"

    private fun pendingMarker(reply: ReviewNote) = "${InlineFindingThread.PENDING_MARKER_PREFIX}${reply.id} -->"

    private data class Todo(
        val thread: InlineFindingThread,
        val reply: ReviewNote,
        val command: ThreadCommand,
        val finding: Finding?,
        val retry: Boolean,
    )

    /** FINAL closes the command; WAIT is retried silently every run; FAILED (a failed model call) is retried once. */
    private enum class State { FINAL, WAIT, FAILED }

    /** [text] is the reply; [state] says whether the command is closed by it. */
    private class Outcome(
        val text: String,
        val state: State = State.FINAL,
    )

    private suspend fun outcomeFor(
        command: ThreadCommand,
        reply: ReviewNote,
        finding: Finding?,
        provenance: ReviewProvenance?,
        asker: ThreadAsker?,
        askEnabled: Boolean,
        language: String?,
    ): Outcome =
        when {
            finding == null -> {
                Outcome(ThreadAnswerText.NOT_IN_RUN.of(language), State.WAIT)
            }

            command == ThreadCommand.WHY -> {
                Outcome(why(finding, provenance, language))
            }

            asker == null -> {
                if (askEnabled) {
                    Outcome(ThreadAnswerText.ASK_NO_PROVIDER.of(language), State.WAIT)
                } else {
                    Outcome(ThreadAnswerText.ASK_DISABLED.of(language))
                }
            }

            else -> {
                val question = InlineMrOverrideParser.askQuestion(reply.body)
                when {
                    question == null -> Outcome(ThreadAnswerText.ASK_EMPTY.of(language))

                    else -> asker.ask(finding, question)?.let { Outcome(it) }
                        ?: Outcome(ThreadAnswerText.ASK_FAILED.of(language), State.FAILED)
                }
            }
        }

    private fun why(
        finding: Finding,
        provenance: ReviewProvenance?,
        language: String?,
    ): String {
        val analyzer = provenance?.analyzers?.firstOrNull { it.ruleId == finding.rule }
        val model = finding.metadata[WellKnownMetadataKeys.AI_MODEL] ?: analyzer?.model
        val lines = buildList {
            add(ThreadAnswerText.WHY_TITLE.of(language))
            add(
                model?.let { ThreadAnswerText.ANALYZER_MODEL.of(language, finding.rule, it) }
                    ?: ThreadAnswerText.ANALYZER_NOT_AI.of(language, finding.rule),
            )
            finding.metadata[WellKnownMetadataKeys.WHY]?.let { add(ThreadAnswerText.REASON.of(language, it)) }
            finding.metadata[AiFindingMetadataKeys.INSTRUCTION]?.let { add(ThreadAnswerText.INSTRUCTION.of(language, it)) }
            finding.metadata[AiFindingMetadataKeys.LEARNING]?.let { add(ThreadAnswerText.LEARNING.of(language, it)) }
            finding.confidence?.let { add(ThreadAnswerText.CONFIDENCE.of(language, it)) }
            provenance?.enrichers?.takeIf { it.isNotEmpty() }?.let { list ->
                add(ThreadAnswerText.CONTEXT_SOURCES.of(language, list.joinToString { "`${it.id}` (${it.status.name.lowercase()})" }))
            }
            add(ThreadAnswerText.FILTERS.of(language, filters(provenance, language)))
            provenance?.coverage?.let { add(ThreadAnswerText.COVERAGE.of(language, it.describe(language))) }
        }
        return lines.joinToString("\n")
    }

    private fun filters(
        provenance: ReviewProvenance?,
        language: String?,
    ): String =
        provenance
            ?.filters
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString { ThreadAnswerText.FILTER_REMOVED.of(language, it.stage, it.removed) }
            ?.let { ThreadAnswerText.FILTERS_REMOVED_BY.of(language, it) }
            ?: ThreadAnswerText.FILTERS_NONE.of(language)
}
