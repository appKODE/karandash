package io.karandash.cli.commands.stats.audit

import io.karandash.api.finding.CommentMarkers
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable

/** Bot notes of one MR judged to be the same finding; [notes] in posting order. */
@Serializable
data class FindingCluster(
    val path: String,
    val rule: String,
    val notes: List<CorpusNote>,
)

/**
 * Frozen content matcher for the audit, independent of karandash's own `FindingFingerprint` so baselines stay
 * comparable when that changes: within one MR, notes on the same path whose normalized-message token sets have
 * Jaccard >= [THRESHOLD] are one cluster.
 */
object ClusterMatcher {
    const val THRESHOLD = 0.8
    const val UNKNOWN_RULE = "unknown"
    private const val MARKER_START = CommentMarkers.PREFIX
    private const val COMMENT_END = "-->"
    private const val MARKDOWN_CHARS = "*_`#~>"

    /** Clusters [notes] (all from one MR) greedily in posting order against each cluster's first note. */
    fun cluster(notes: List<CorpusNote>): List<FindingCluster> {
        val clusters = mutableListOf<Pair<Set<String>, MutableList<CorpusNote>>>()
        for (note in notes.sortedWith(compareBy({ it.createdAt }, { it.id }))) {
            val tokens = tokens(note.body)
            val path = pathOf(note)
            val match = clusters.firstOrNull { (first, members) -> pathOf(members[0]) == path && jaccard(first, tokens) >= THRESHOLD }
            if (match != null) match.second += note else clusters += tokens to mutableListOf(note)
        }
        return clusters.map { (_, members) -> FindingCluster(pathOf(members[0]), ruleOf(members[0].body), members.toList()) }
    }

    /** Lowercase, karandash marker comments and markdown emphasis removed, whitespace collapsed. */
    fun normalize(body: String): String {
        val noMarkers = StringBuilder()
        var i = 0
        while (i < body.length) {
            val start = body.indexOf(MARKER_START, i)
            if (start < 0) {
                noMarkers.append(body, i, body.length)
                break
            }
            noMarkers.append(body, i, start)
            val end = body.indexOf(COMMENT_END, start)
            i = if (end < 0) body.length else end + COMMENT_END.length
        }
        return noMarkers
            .filterNot { it in MARKDOWN_CHARS }
            .split(' ', '\n', '\t', '\r')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .lowercase()
    }

    fun tokens(body: String): Set<String> =
        normalize(body)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .toSet()

    fun jaccard(
        a: Set<String>,
        b: Set<String>,
    ): Double = if (a.isEmpty() && b.isEmpty()) 1.0 else a.intersect(b).size.toDouble() / a.union(b).size

    /** Rule id: the karandash finding marker, else a leading kebab-case id (`**rule-id**: ...`), else [UNKNOWN_RULE]. */
    fun ruleOf(body: String): String {
        InlineFindingMarker.parse(body).firstOrNull()?.let { return it.rule }
        val head = body
            .lineSequence()
            .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith(MARKER_START) }
            ?.filterNot { it in "*[]`" }
            ?.trim()
            ?.takeWhile { !it.isWhitespace() && it != ':' }
            .orEmpty()
        val kebab = head.isNotEmpty() && head[0].isLowerCase() && '-' in head && head.all { it.isLowerCase() || it.isDigit() || it == '-' }
        return if (kebab) head else UNKNOWN_RULE
    }

    /** Severity name of the note header `**<emoji> SEVERITY**`, else `null`. */
    fun severityOf(body: String): String? {
        val start = body.indexOf(BOLD)
        val end = body.indexOf(BOLD, start + BOLD.length)
        if (start < 0 || end < 0) return null
        val word = body.substring(start + BOLD.length, end).trim().substringAfterLast(' ')
        return Severity.entries.firstOrNull { it.name == word }?.name
    }

    private const val BOLD = "**"

    private fun pathOf(note: CorpusNote): String = note.position?.newPath.orEmpty()
}
