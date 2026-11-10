package io.karandash.plugins.outputs

import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.InlineFindingThread
import io.karandash.api.finding.InlineThreadMatch
import io.karandash.api.finding.InlineThreadMatcher
import io.karandash.utils.finding.AnchorFingerprint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a run's new inline comments reach the platform. */
@Serializable
enum class InlinePublish {
    /** As one review where the platform has one (GitLab draft notes, GitHub review, Gerrit ReviewInput): one notification. */
    @SerialName("batch")
    BATCH,

    /** One comment at a time: one notification each. */
    @SerialName("each")
    EACH,
}

/** What the bot does with its thread once the code it pointed at is fixed. */
@Serializable
enum class InlineOnFixed {
    /** Edit its own note (`✅ Fixed in <sha>`, header struck through) and resolve the thread: no new note. */
    @SerialName("edit")
    EDIT,

    /** Resolve with a "Fixed in" note, or reply "Looks fixed" in a thread a person answered. */
    @SerialName("reply")
    REPLY,
}

/**
 * `inline` block of [PlatformOutputConfig].
 *
 * @property maxPerRun New inline comments posted per run; the rest are listed collapsed in the summary.
 * @property publish [InlinePublish.BATCH] by default.
 * @property onFixed [InlineOnFixed.EDIT] by default.
 */
@Serializable
data class InlineLifecycleConfig(
    val maxPerRun: Int = DEFAULT_MAX_PER_RUN,
    val publish: InlinePublish = InlinePublish.BATCH,
    val onFixed: InlineOnFixed = InlineOnFixed.EDIT,
) {
    companion object {
        /** Default [maxPerRun]. */
        const val DEFAULT_MAX_PER_RUN = 8
    }
}

/**
 * Pure decisions of the reconcile lifecycle: which inline comments are new, which earlier threads are fixed, and the
 * summary delta. No platform calls; [PlatformOutputModule] carries them out.
 */
internal object InlineReconciler {
    /**
     * Matches [comments] (by the finding markers in their bodies) to [threads]; open threads win over resolved ones.
     * A resolved thread only absorbs a comment when a person was involved (a reply, or someone other than the bot
     * resolved it): a thread the bot resolved as fixed on its own does not hide a regression.
     */
    fun match(
        comments: List<InlineCommentData>,
        threads: List<InlineFindingThread>,
    ): InlineThreadMatch<InlineCommentData, InlineFindingThread> =
        InlineThreadMatcher.match(
            comments,
            { InlineFindingMarker.parse(it.body) },
            threads
                .filter { !it.thread.resolved || it.humanReplies.isNotEmpty() || resolvedByPerson(it) }
                .sortedBy { it.thread.resolved }
                .associateWith { it.refs },
        )

    // An unknown resolver counts as a person: never repost over a resolve the bot cannot attribute to itself. A
    // thread the bot marked fixed was resolved by the bot even where the platform reports no resolver.
    private fun resolvedByPerson(thread: InlineFindingThread): Boolean {
        val markedFixed =
            InlineReconcileStage.isFixedNote(thread.botNote.body) ||
                thread.hasBotNote(InlineFindingThread.FIXED_MARKER)
        if (markedFixed) return false
        val resolver = thread.thread.notes.firstNotNullOfOrNull { it.resolvedBy }
        return resolver == null || resolver != thread.botNote.author
    }

    /** Distinct open threads that a current comment matched. */
    fun openCount(match: InlineThreadMatch<InlineCommentData, InlineFindingThread>): Int =
        match.matched.values
            .distinct()
            .count { !it.thread.resolved }

    /** Unmatched open threads whose fix can be checked: every ref carries an anchor fingerprint. */
    fun candidates(match: InlineThreadMatch<InlineCommentData, InlineFindingThread>): List<InlineFindingThread> =
        match.unmatchedThreads.filter { t ->
            !t.thread.resolved && t.path != null && t.refs.isNotEmpty() &&
                t.refs.all { it.fpv.isNotEmpty() }
        }

    /**
     * Whether [thread]'s anchored lines are all gone from its file at head. False when the head content of the file
     * is unknown (absent from [headContent]) or a ref has no anchor fingerprint.
     */
    fun isFixed(
        thread: InlineFindingThread,
        headContent: Map<String, String>,
    ): Boolean {
        val path = thread.path ?: return false
        val content = headContent[path] ?: return false
        if (thread.refs.isEmpty() || thread.refs.any { it.fpv.isEmpty() }) return false
        return thread.refs.none { ref -> ref.fpv in AnchorFingerprint.fingerprintsIn(ref.rule, ref.path ?: path, content) }
    }

    /** Whether the bot already told [thread] that it looks fixed. */
    fun hasLooksFixedReply(thread: InlineFindingThread): Boolean = thread.hasBotNote(InlineFindingThread.LOOKS_FIXED_MARKER)

    /**
     * [comments] most severe first (ERROR, WARNING, INFO, then the rest), report order within a severity, so a
     * per-run cap drops the least severe. Severity is read from the note title the markdown format writes.
     */
    fun bySeverity(comments: List<InlineCommentData>): List<InlineCommentData> =
        comments.sortedBy { c -> SEVERITY_TITLES.indexOfFirst { it in c.body }.takeIf { it >= 0 } ?: SEVERITY_TITLES.size }

    private val SEVERITY_TITLES = listOf(" ERROR**", " WARNING**", " INFO**")

    /**
     * The summary's `open / fixed` line, with [overflow] (not posted inline) in a collapsed list; empty when all are
     * zero. [openCount] counts the threads posted this run too: a thread new on one run is open on the next, and the
     * line stays the same, so it does not edit the summary.
     */
    fun summarySection(
        openCount: Int,
        fixedCount: Int,
        overflow: List<InlineCommentData>,
        language: String? = null,
    ): String {
        if (openCount == 0 && fixedCount == 0 && overflow.isEmpty()) return ""
        val delta = InlineLifecycleText.DELTA.of(language, openCount, fixedCount)
        if (overflow.isEmpty()) return delta
        val items = overflow.joinToString("\n") { c ->
            val ref = InlineFindingMarker.parse(c.body).firstOrNull()
            "- `${c.filePath}:${c.line}`" + ref?.let { " `${it.rule}`: ${it.messagePrefix}" }.orEmpty()
        }
        return "$delta\n\n<details><summary>${InlineLifecycleText.OVERFLOW.of(language, overflow.size)}</summary>\n\n$items\n\n</details>"
    }

    /** The summary notice in [language] when review threads could not be listed and no inline comment was posted. */
    fun listingFailedNotice(language: String?): String = InlineLifecycleText.LISTING_FAILED.of(language)
}
