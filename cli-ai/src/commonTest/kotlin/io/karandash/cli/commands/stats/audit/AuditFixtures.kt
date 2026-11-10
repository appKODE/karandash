package io.karandash.cli.commands.stats.audit

import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestFiles
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.random.Random

/** Synthetic review-history corpus for the audit tests; no real client data. */
internal object AuditFixtures {
    const val BOT = "karandash-bot"

    /** Prefix karandash writes on every note; `stats --audit` counts only bots whose notes carry it. */
    const val MARKER = "<!-- karandash-inline -->\n"

    fun botNote(
        id: Long,
        body: String,
        path: String? = "src/App.kt",
        line: Int = 10,
        awards: List<CorpusAward> = emptyList(),
        resolvedBy: String? = null,
        anchor: CorpusAnchor? = null,
    ): CorpusNote =
        CorpusNote(
            id = id,
            author = BOT,
            isBot = true,
            body = MARKER + body,
            createdAt = "2026-01-01T10:00:${(id % 60).toString().padStart(2, '0')}Z",
            resolved = resolvedBy != null,
            resolvedBy = resolvedBy,
            position = path?.let { CorpusPosition(newPath = it, newLine = line, headSha = "sha1") },
            awards = awards,
            anchor = anchor,
        )

    fun reply(
        id: Long,
        body: String,
        author: String = "alice",
    ): CorpusNote = CorpusNote(id = id, author = author, body = body, createdAt = "2026-01-01T11:00:00Z")

    fun mr(
        project: String,
        iid: Int,
        createdAt: String,
        discussions: List<List<CorpusNote>>,
        diffHash: String = "$project-$iid",
        versions: Int = 1,
    ): CorpusMr =
        CorpusMr(
            project = project,
            iid = iid,
            createdAt = createdAt,
            diffHash = diffHash,
            versions = (1..versions).map { CorpusVersion(headSha = "sha$it", createdAt = createdAt) },
            discussions = discussions.mapIndexed { i, notes -> CorpusDiscussion(id = "d$iid-$i", notes = notes) },
        )

    fun anchor(
        snippet: List<String>,
        finalSnippet: List<String>? = snippet,
    ): CorpusAnchor = CorpusAnchor(startLine = 7, snippet = snippet, finalSnippet = finalSnippet)

    private val code = listOf("fun a() {", "    val x = 1", "    call(x)", "    val y = load()", "    use(y)", "    return", "}")

    /** Four MRs over two projects, covering every label and the summary/coverage parse. */
    fun corpus(): List<CorpusMr> =
        listOf(
            mr(
                "alpha",
                1,
                "2026-01-05T09:00:00Z",
                listOf(
                    listOf(
                        botNote(1, "**null-safety** `load()` may return null", anchor = anchor(code)),
                        reply(2, "fixed, thanks"),
                    ),
                    listOf(botNote(3, "**null-safety** `load()` may return null!", line = 11, anchor = anchor(code))),
                    listOf(botNote(4, "Reviewed 3/4 files. Summary of findings", path = null)),
                ),
                versions = 2,
            ),
            mr(
                "alpha",
                2,
                "2026-02-10T09:00:00Z",
                listOf(
                    listOf(botNote(5, "Magic number `42` here", anchor = anchor(code)), reply(6, "это ложное срабатывание")),
                    listOf(
                        botNote(7, "Unused import", awards = listOf(CorpusAward("thumbsup", "bob")), anchor = anchor(code)),
                    ),
                ),
            ),
            mr(
                "beta",
                3,
                "2026-03-01T09:00:00Z",
                listOf(
                    listOf(botNote(8, "Long method", anchor = anchor(code, code.reversed()))),
                    listOf(botNote(9, "Possible race", resolvedBy = BOT, anchor = anchor(code))),
                    listOf(botNote(10, "Why this?", anchor = anchor(code)), reply(11, "why?")),
                ),
                versions = 3,
            ),
            mr(
                "beta",
                4,
                "2026-04-01T09:00:00Z",
                listOf(listOf(botNote(12, "Reviewed 5/5 files.", path = null))),
            ),
        )

    /** A bot summary carrying the provenance block the markdown format writes: coverage line plus enricher table. */
    fun provenanceSummary(
        id: Long,
        coverage: String,
        enrichers: List<Pair<String, String>>,
        label: String = "Coverage",
    ): CorpusNote {
        val table = if (enrichers.isEmpty()) {
            ""
        } else {
            "| Enricher | Status | Details |\n|---|---|---|\n" + enrichers.joinToString("") { (e, s) -> "| `$e` | $s |  |\n" } + "\n"
        }
        val block = "<details><summary>How this review was made</summary>\n\n**$label:** $coverage\n\n$table</details>\n"
        return botNote(id, "Summary of findings\n$block", path = null)
    }

    /**
     * [corpus] with rule ids on every finding and provenance summaries: alpha 1 full context, alpha 2 a failed
     * enricher, beta 3 context cut by the budget; beta 3 also gets a thumbs-down so it has a labelled cluster.
     */
    fun rootCauseCorpus(): List<CorpusMr> {
        val rules = mapOf(5L to "magic-number", 7L to "unused-import", 8L to "long-method", 9L to "race-condition", 10L to "naming-style")
        val full = listOf("jira" to "ok", "codeowners" to "ok")
        val failed = listOf("jira" to "failed", "codeowners" to "ok")
        val summaries = mapOf(
            "alpha" to 1 to provenanceSummary(4, "reviewed 3/4 files, 0 chunks skipped, 0 truncated", full),
            "alpha" to 2 to provenanceSummary(13, "reviewed 12/12 files, 0 chunks skipped, 0 truncated", failed),
            "beta" to 3 to
                provenanceSummary(14, "reviewed 40/80 files, 1 chunk skipped, context left out: a.md", emptyList()),
        )
        return corpus().map { m ->
            val notes = m.discussions
                .map { d ->
                    d.copy(
                        notes = d.notes.filter { it.id != 4L }.map { n ->
                            val ruled = rules[n.id]?.let { n.copy(body = MARKER + "**$it** " + n.body.removePrefix(MARKER)) } ?: n
                            if (n.id == 10L) ruled.copy(awards = listOf(CorpusAward("thumbsdown", "bob"))) else ruled
                        },
                    )
                }.filter { it.notes.isNotEmpty() }
            val summary = summaries[m.project to m.iid]
            m.copy(discussions = notes + listOfNotNull(summary?.let { CorpusDiscussion(id = "s${m.iid}", notes = listOf(it)) }))
        }
    }

    /** Writes [mrs] under a fresh temp dir as `<dir>/<project>/<iid>.json` plus a manifest; returns the dir. */
    fun writeCorpus(mrs: List<CorpusMr>): Path {
        val dir = TestFiles.createTestTempDirectory("karandash-audit")
        for (m in mrs) {
            val projectDir = Path(dir, m.project)
            SystemFileSystem.createDirectories(projectDir)
            write(Path(projectDir, "${m.iid}.json"), KarandashJson.Pretty.encodeToString(CorpusMr.serializer(), m))
        }
        write(Path(dir, "manifest.json"), "{\"projects\": []}")
        return dir
    }

    private fun write(
        path: Path,
        text: String,
    ) {
        SystemFileSystem.sink(path).buffered().use { it.writeString(text) }
    }
}
