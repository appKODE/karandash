package io.karandash.cli.commands.stats.audit

import io.karandash.api.finding.CommentMarkers
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.ReplyClassifier
import io.karandash.plugins.ai.core.ReplyJudge
import io.karandash.plugins.ai.core.ReplyVerdict
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/** Options of `karandash stats --audit`. */
@Serializable
data class AuditOptions(
    /** Karandash bot usernames, on top of `isBot` authors whose notes carry the `<!-- karandash` marker. */
    val bots: Set<String> = emptySet(),
    val seed: Int = 0,
    /** `YYYY-MM-DD`; MRs created before it tune, the rest test. */
    val cutoff: String? = null,
    val holdoutProject: String? = null,
    val resamples: Int = AuditMetrics.DEFAULT_RESAMPLES,
)

/** The audit report: settings plus metrics per split. */
@Serializable
data class AuditReport(
    val seed: Int,
    val resamples: Int,
    val cutoff: String?,
    val holdoutProject: String?,
    val bots: List<String>,
    val mrs: Int,
    val duplicatesDropped: Int,
    val all: SplitMetrics,
    val tune: SplitMetrics,
    val test: SplitMetrics,
    val holdout: SplitMetrics?,
    /** Labelled clusters of all MRs by cause × analyzer × context × MR size. */
    val rootCauses: List<RootCauseRow> = emptyList(),
)

/** One audit: the report, its JSON and a short human table. */
@Serializable
data class AuditRun(
    val report: AuditReport,
    val json: String,
    val table: String,
    /** [AuditReport.rootCauses] as a table. */
    val rootCauseTable: String = "",
)

/** Pure pipeline behind `stats --audit`: read corpus, cluster, label, split, measure. Same corpus + seed, same bytes. */
object StatsAuditRunner {
    private const val REVIEWED = "reviewed "
    private const val MARKER = CommentMarkers.PREFIX

    /** [judged]: `--llm-judge` verdicts by reply note id ([judgeReplies]). */
    fun run(
        dir: Path,
        options: AuditOptions,
        judged: Map<Long, ReplyVerdict> = emptyMap(),
    ): AuditRun {
        val corpus = CorpusReader.read(dir)
        val bots = bots(corpus, options)
        val splits = AuditSplits.split(corpus, options.cutoff, options.holdoutProject)
        val audits = splits.all.associateWith { audit(it, bots, judged) }
        val metrics = { mrs: List<CorpusMr> -> AuditMetrics.compute(mrs.map { audits.getValue(it) }, options.seed, options.resamples) }
        val report = AuditReport(
            seed = options.seed,
            resamples = options.resamples,
            cutoff = options.cutoff,
            holdoutProject = options.holdoutProject,
            bots = bots.sorted(),
            mrs = splits.all.size,
            duplicatesDropped = splits.duplicatesDropped,
            all = metrics(splits.all),
            tune = metrics(splits.tune),
            test = metrics(splits.test),
            holdout = options.holdoutProject?.let { metrics(splits.holdout) },
            rootCauses = AuditRootCause.breakdown(audits.values.toList()),
        )
        val json = KarandashJson.Pretty.encodeToString(AuditReport.serializer(), report)
        return AuditRun(report, json, AuditTable.render(report), AuditRootCause.render(report.rootCauses))
    }

    /** Audited MRs of the corpus in [dir], duplicates dropped; what `calibrate --corpus` learns from. */
    fun audits(
        dir: Path,
        options: AuditOptions,
    ): List<MrAudit> {
        val corpus = CorpusReader.read(dir)
        val bots = bots(corpus, options)
        return AuditSplits.split(corpus, null, null).all.map { audit(it, bots) }
    }

    /**
     * `stats --audit --llm-judge`: asks [judge] about every reply to an inline karandash note that the classifier
     * leaves unknown, with the note's finding text. The verdicts other than unknown, by reply note id.
     */
    suspend fun judgeReplies(
        dir: Path,
        options: AuditOptions,
        judge: ReplyJudge,
    ): Map<Long, ReplyVerdict> {
        val corpus = CorpusReader.read(dir)
        val bots = bots(corpus, options)
        val judged = mutableMapOf<Long, ReplyVerdict>()
        for (mr in AuditSplits.split(corpus, options.cutoff, options.holdoutProject).all) {
            val notes = mr.discussions.flatMap { it.notes }.associateBy { it.id }
            for ((noteId, replies) in repliesByNote(mr, bots)) {
                val note = notes.getValue(noteId)
                if (note.system || note.position?.newPath == null) continue
                replies
                    .filter { it.id !in judged && ReplyClassifier.classify(it.body) == ReplyVerdict.UNKNOWN }
                    .forEach { reply -> judged[reply.id] = judge.judge(reply.body, ReplyJudge.excerpt(note.body)) }
            }
        }
        return judged.filterValues { it != ReplyVerdict.UNKNOWN }
    }

    // other bots (CI, danger, ...) are neither audited nor counted as human replies
    private fun bots(
        corpus: List<CorpusMr>,
        options: AuditOptions,
    ): Set<String> =
        options.bots +
            corpus.flatMap { m -> m.discussions.flatMap { d -> d.notes.filter { it.isBot && MARKER in it.body }.map { it.author } } }

    /**
     * Per-MR inputs: notes of the karandash [bots], clustered inline findings with their labels, anchor checks and
     * summaries. Replies are later non-system notes of the thread by neither a karandash bot nor any `isBot` author.
     */
    fun audit(
        mr: CorpusMr,
        bots: Set<String>,
        judged: Map<Long, ReplyVerdict> = emptyMap(),
    ): MrAudit {
        val isKarandash = { n: CorpusNote -> n.author in bots }
        val replies = repliesByNote(mr, bots)
        val commits = (mr.versions.map { it.headSha } + mr.finalHeadSha).filter { it.isNotEmpty() }.toSet()
        val botNotes = mr.discussions.flatMap { it.notes }.filter { !it.system && isKarandash(it) }
        val inline = botNotes.filter { it.position?.newPath != null }
        val summaries = botNotes.filter { it.position?.newPath == null }.sortedByDescending { it.createdAt }
        val clusters = ClusterMatcher.cluster(inline).map { c ->
            val r = c.notes.flatMap { replies[it.id].orEmpty() }.distinctBy { it.id }
            ClusterOutcome(
                ClusterLabeler.label(c.notes, r, bots, commits, judged),
                hasReply = r.isNotEmpty(),
                rule = c.rule,
                path = c.path,
                severity = ClusterMatcher.severityOf(c.notes[0].body)
            )
        }
        return MrAudit(
            project = mr.project,
            iid = mr.iid,
            botNotes = botNotes.size,
            findingNotes = inline.size,
            pushes = mr.versions.size,
            clusters = clusters,
            anchorChecks = inline.mapNotNull(AnchorCheck::check),
            coverage = summaries.firstNotNullOfOrNull { coverage(it.body) },
            summaryBytes = summaries.map { it.body.encodeToByteArray().size },
            context = summaries.map { AuditRootCause.context(it.body) }.firstOrNull { it != ClusterContext() } ?: ClusterContext(),
        )
    }

    /** Replies by karandash note id: later non-system notes of its thread by neither a karandash bot nor any `isBot` author. */
    private fun repliesByNote(
        mr: CorpusMr,
        bots: Set<String>,
    ): Map<Long, List<CorpusNote>> {
        val replies = mutableMapOf<Long, List<CorpusNote>>()
        for (d in mr.discussions) {
            d.notes.forEachIndexed { i, n ->
                if (n.author in bots) replies[n.id] = d.notes.drop(i + 1).filter { !it.system && !it.isBot && it.author !in bots }
            }
        }
        return replies
    }

    /** `reviewed X/Y files` (any case) as X/Y, else `null`. */
    fun coverage(body: String): Double? = coverageCounts(body)?.let { (x, y) -> x.toDouble() / y }

    /** `reviewed X/Y files` (any case) as `(X, Y)` with Y > 0, else `null`. */
    fun coverageCounts(body: String): Pair<Int, Int>? {
        val text = body.lowercase()
        val at = text.indexOf(REVIEWED)
        if (at < 0) return null
        val rest = text.substring(at + REVIEWED.length)
        val reviewed = rest.takeWhile { it.isDigit() }
        val total = rest.drop(reviewed.length).removePrefix("/").takeWhile { it.isDigit() }
        val y = total.toIntOrNull() ?: return null
        val x = reviewed.toIntOrNull() ?: return null
        return if (y > 0 && rest.drop(reviewed.length + 1 + total.length).trimStart().startsWith("file")) x to y else null
    }
}
