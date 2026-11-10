package io.karandash.cli.commands.stats.audit

import io.karandash.plugins.ai.core.ReplyClassifier
import io.karandash.plugins.ai.core.ReplyEvidence
import io.karandash.plugins.ai.core.ReplyReaction
import io.karandash.plugins.ai.core.ReplyVerdict
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Outcome label of one finding cluster. */
@Serializable
enum class ClusterLabel {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("rejected")
    REJECTED,

    @SerialName("outdated")
    OUTDATED,

    @SerialName("bot-resolved")
    BOT_RESOLVED,

    @SerialName("unknown")
    UNKNOWN,
}

/**
 * Labels a finding cluster. Precedence: explicit reply verdict (a reply the reaction contradicts is unknown, and so is
 * the cluster) > thumbs (any skin tone) or check-mark reaction on a bot note >
 * resolved by a human with a reply plus a code change at the anchor (accepted) > anchor changed with no human
 * reply (outdated) > resolved by the bot itself (bot-resolved) > unknown.
 */
object ClusterLabeler {
    /**
     * [replies] are the human, non-system notes that follow any of [notes] in their threads; [commits] are the MR's
     * version head shas, so a reply citing one of them cites a fix commit. [judged] holds `--llm-judge` verdicts by
     * reply note id, used for a reply the classifier leaves unknown.
     */
    fun label(
        notes: List<CorpusNote>,
        replies: List<CorpusNote>,
        bots: Set<String>,
        commits: Set<String> = emptySet(),
        judged: Map<Long, ReplyVerdict> = emptyMap(),
    ): ClusterLabel {
        val reaction = ReplyReaction.of(notes.flatMap { it.awards }.filter { it.user !in bots }.map { it.name })
        val verdicts = replies
            .map { reply ->
                val verdict = ReplyClassifier.classify(ReplyEvidence(reply.body, citesCommit(reply.body, commits), reaction))
                if (verdict == ReplyVerdict.UNKNOWN) judged[reply.id] ?: verdict else verdict
            }.filter { it != ReplyVerdict.UNKNOWN }
            .toSet()
        if (verdicts.size == 1) return if (verdicts.single() == ReplyVerdict.ACCEPTED) ClusterLabel.ACCEPTED else ClusterLabel.REJECTED
        if (reaction != null && verdicts.isEmpty() && replies.any { ReplyClassifier.classify(it.body) != ReplyVerdict.UNKNOWN }) {
            return ClusterLabel.UNKNOWN
        }
        if (reaction != null) return if (reaction == ReplyReaction.UP) ClusterLabel.ACCEPTED else ClusterLabel.REJECTED

        val codeChanged = notes.any { anchorChanged(it) }
        val resolver = notes.firstNotNullOfOrNull { it.resolvedBy }
        return when {
            resolver != null && resolver !in bots && replies.isNotEmpty() && codeChanged -> ClusterLabel.ACCEPTED
            codeChanged && replies.isEmpty() -> ClusterLabel.OUTDATED
            resolver != null && resolver in bots -> ClusterLabel.BOT_RESOLVED
            else -> ClusterLabel.UNKNOWN
        }
    }

    /**
     * `true` when [reply] cites a sha prefixing one of [commits], else `null`: version heads miss the inner commits of
     * a push, so a sha matching none of them is not evidence of a foreign commit.
     */
    private fun citesCommit(
        reply: String,
        commits: Set<String>,
    ): Boolean? = if (ReplyClassifier.citedShas(reply).any { sha -> commits.any { it.lowercase().startsWith(sha) } }) true else null

    /** The ±3-line window at the note's head differs from the same window at the final head (or the file is gone). */
    fun anchorChanged(note: CorpusNote): Boolean {
        val anchor = note.anchor ?: return false
        return anchor.finalSnippet != anchor.snippet
    }
}
