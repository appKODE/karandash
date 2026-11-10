package io.karandash.cli.commands.stats.audit

import io.karandash.cli.commands.stats.audit.AuditFixtures.mr
import io.karandash.plugins.ai.core.ReplyJudge
import io.karandash.plugins.ai.core.ReplyVerdict
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsAuditRunnerTest {
    private fun options(seed: Int = 7) = AuditOptions(bots = setOf(AuditFixtures.BOT), seed = seed, cutoff = "2026-02-15")

    @Test
    fun `audit replay of frozen corpus is deterministic`() {
        val dir = AuditFixtures.writeCorpus(AuditFixtures.corpus())

        val first = StatsAuditRunner.run(dir, options())
        val second = StatsAuditRunner.run(dir, options())

        assertEquals(first.json, second.json)
        assertTrue(first.json.contains("\"mrs\": 4"), first.json)
    }

    @Test
    fun `fixture corpus labels every cluster kind`() {
        val all = StatsAuditRunner.run(AuditFixtures.writeCorpus(AuditFixtures.corpus()), options()).report.all

        assertEquals(LabelCounts(accepted = 2, rejected = 1, outdated = 1, unknown = 1, botResolved = 1), all.labels)
        assertEquals(6, all.clusters)
        assertEquals(7, all.findingNotes)
        assertEquals(0.875, all.coverage.perMr)
    }

    @Test
    fun `cutoff puts early mrs in tune and late mrs in test`() {
        val report = StatsAuditRunner.run(AuditFixtures.writeCorpus(AuditFixtures.corpus()), options()).report

        assertEquals(2, report.tune.mrs)
        assertEquals(2, report.test.mrs)
    }

    @Test
    fun `bot flag comes from the bot list when the corpus lacks it`() {
        val unflagged = AuditFixtures.corpus().map { m ->
            m.copy(discussions = m.discussions.map { d -> d.copy(notes = d.notes.map { it.copy(isBot = false) }) })
        }
        val dir = AuditFixtures.writeCorpus(unflagged)

        assertEquals(
            6,
            StatsAuditRunner
                .run(dir, options())
                .report.all.clusters
        )
        assertEquals(
            0,
            StatsAuditRunner
                .run(dir, options().copy(bots = emptySet()))
                .report.all.clusters
        )
    }

    @Test
    fun `only bots whose notes carry the karandash marker are audited`() {
        val other = CorpusNote(
            id = 90,
            author = "ci-helper-bot",
            isBot = true,
            body = "Pipeline passed",
            position = CorpusPosition(newPath = "src/App.kt", newLine = 10),
        )
        val corpus =
            AuditFixtures.corpus() +
                mr("beta", 5, "2026-04-02T09:00:00Z", listOf(listOf(other), listOf(other.copy(id = 91, position = null))))

        val report = StatsAuditRunner.run(AuditFixtures.writeCorpus(corpus), options().copy(bots = emptySet())).report

        assertEquals(listOf(AuditFixtures.BOT), report.bots)
        assertEquals(9, report.all.botNotes)
        assertEquals(7, report.all.findingNotes)
        assertEquals(6, report.all.clusters)
    }

    @Test
    fun `severity is read from the inline note header`() {
        val notes = listOf(
            listOf(AuditFixtures.botNote(1, "**\uD83D\uDD34 ERROR** \u2014 `null-safety` \u00B7 load may return null")),
            listOf(AuditFixtures.botNote(2, "**null-safety** no header", path = "src/B.kt")),
        )

        val clusters = StatsAuditRunner.audit(mr("alpha", 1, "2026-01-05T09:00:00Z", notes), setOf(AuditFixtures.BOT)).clusters

        assertEquals(listOf("ERROR", null), clusters.map { it.severity })
    }

    @Test
    fun `a reply citing an MR version sha labels the cluster accepted`() {
        val notes =
            listOf(
                listOf(
                    AuditFixtures.botNote(1, "**null-safety** load may return null"),
                    AuditFixtures.reply(2, "Добавлен clamp в 1a2b3c4d")
                )
            )
        val base = mr("alpha", 1, "2026-01-05T09:00:00Z", notes)
        val cited = base.copy(versions = listOf(CorpusVersion(headSha = "1a2b3c4d5e6f7a8b")))

        assertEquals(
            ClusterLabel.ACCEPTED,
            StatsAuditRunner
                .audit(cited, setOf(AuditFixtures.BOT))
                .clusters
                .single()
                .label
        )
        assertEquals(
            ClusterLabel.UNKNOWN,
            StatsAuditRunner
                .audit(base, setOf(AuditFixtures.BOT))
                .clusters
                .single()
                .label
        )
    }

    @Test
    fun `llm judge verdicts decide only replies the classifier leaves unknown`() =
        runTest {
            val notes =
                listOf(
                    listOf(
                        AuditFixtures.botNote(1, "**null-safety** load may return null"),
                        AuditFixtures.reply(2, "see the upstream handler")
                    ),
                    listOf(AuditFixtures.botNote(3, "**naming** rename x", line = 40), AuditFixtures.reply(4, "fixed, thanks")),
                )
            val dir = AuditFixtures.writeCorpus(listOf(mr("alpha", 1, "2026-01-05T09:00:00Z", notes)))
            val asked = mutableListOf<Pair<String, String>>()
            val judge = ReplyJudge { reply, excerpt -> ReplyVerdict.REJECTED.also { asked += reply to excerpt } }

            val judged = StatsAuditRunner.judgeReplies(dir, options(), judge)

            assertEquals(mapOf(2L to ReplyVerdict.REJECTED), judged)
            assertEquals(listOf("see the upstream handler" to "**null-safety** load may return null"), asked)
            assertEquals(
                LabelCounts(accepted = 1, unknown = 1),
                StatsAuditRunner
                    .run(dir, options())
                    .report.all.labels
            )
            assertEquals(
                LabelCounts(accepted = 1, rejected = 1),
                StatsAuditRunner
                    .run(dir, options(), judged)
                    .report.all.labels
            )
        }

    @Test
    fun `a judge that stays unknown adds no verdict`() =
        runTest {
            val notes = listOf(listOf(AuditFixtures.botNote(1, "**null-safety** x"), AuditFixtures.reply(2, "hm")))
            val dir = AuditFixtures.writeCorpus(listOf(mr("alpha", 1, "2026-01-05T09:00:00Z", notes)))

            assertEquals(emptyMap(), StatsAuditRunner.judgeReplies(dir, options()) { _, _ -> ReplyVerdict.UNKNOWN })
        }
}
