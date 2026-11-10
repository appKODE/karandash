package io.karandash.cli.commands.stats.audit

import io.karandash.cli.commands.stats.audit.AuditFixtures.BOT
import io.karandash.cli.commands.stats.audit.AuditFixtures.anchor
import io.karandash.cli.commands.stats.audit.AuditFixtures.botNote
import io.karandash.cli.commands.stats.audit.AuditFixtures.reply
import kotlin.test.Test
import kotlin.test.assertEquals

class ClusterLabelerTest {
    private val bots = setOf(BOT)
    private val same = anchor(listOf("a", "b", "c"))
    private val changed = anchor(listOf("a", "b", "c"), listOf("a", "B", "c"))
    private val up = CorpusAward("thumbsup", "bob")
    private val down = CorpusAward("thumbsdown", "bob")

    private fun label(
        note: CorpusNote,
        vararg replies: CorpusNote,
    ) = ClusterLabeler.label(listOf(note), replies.toList(), bots)

    @Test
    fun `dismissive reply labels rejected`() {
        assertEquals(ClusterLabel.REJECTED, label(botNote(1, "x", anchor = same), reply(2, "не баг")))
    }

    @Test
    fun `neutral reply stays unknown`() {
        assertEquals(ClusterLabel.UNKNOWN, label(botNote(1, "x", anchor = same), reply(2, "ok let me see")))
    }

    @Test
    fun `reply verdict beats a missing or agreeing reaction`() {
        assertEquals(ClusterLabel.ACCEPTED, label(botNote(1, "x", anchor = same), reply(2, "fixed")))
        assertEquals(ClusterLabel.ACCEPTED, label(botNote(1, "x", awards = listOf(up), anchor = same), reply(2, "fixed")))
    }

    @Test
    fun `reply against the reaction stays unknown`() {
        assertEquals(ClusterLabel.UNKNOWN, label(botNote(1, "x", awards = listOf(down), anchor = same), reply(2, "fixed")))
        assertEquals(ClusterLabel.UNKNOWN, label(botNote(1, "x", awards = listOf(up), anchor = same), reply(2, "false positive")))
    }

    @Test
    fun `neutral reply citing an MR commit is accepted`() {
        val commits = setOf("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b")
        val note = botNote(1, "x", anchor = same)
        assertEquals(
            ClusterLabel.ACCEPTED,
            ClusterLabeler.label(listOf(note), listOf(reply(2, "Добавлен clamp в 1a2b3c4d")), bots, commits)
        )
        assertEquals(ClusterLabel.UNKNOWN, ClusterLabeler.label(listOf(note), listOf(reply(2, "Добавлен clamp в 9f9f9f9f")), bots, commits))
        assertEquals(ClusterLabel.UNKNOWN, label(note, reply(2, "Добавлен clamp в 1a2b3c4d")))
    }

    @Test
    fun `thumbs reactions label accepted or rejected`() {
        assertEquals(ClusterLabel.ACCEPTED, label(botNote(1, "x", awards = listOf(up), anchor = same)))
        assertEquals(ClusterLabel.REJECTED, label(botNote(1, "x", awards = listOf(down), anchor = same)))
    }

    @Test
    fun `bot own reaction is ignored`() {
        assertEquals(ClusterLabel.UNKNOWN, label(botNote(1, "x", awards = listOf(CorpusAward("thumbsup", BOT)), anchor = same)))
    }

    @Test
    fun `human resolve with reply and code change labels accepted`() {
        val note = botNote(1, "x", resolvedBy = "alice", anchor = changed)
        assertEquals(ClusterLabel.ACCEPTED, label(note, reply(2, "see commit")))
    }

    @Test
    fun `human resolve with reply but no code change stays unknown`() {
        val note = botNote(1, "x", resolvedBy = "alice", anchor = same)
        assertEquals(ClusterLabel.UNKNOWN, label(note, reply(2, "see commit")))
    }

    @Test
    fun `code change without reply labels outdated`() {
        assertEquals(ClusterLabel.OUTDATED, label(botNote(1, "x", anchor = changed)))
        assertEquals(ClusterLabel.OUTDATED, label(botNote(1, "x", anchor = anchor(listOf("a"), null))))
    }

    @Test
    fun `resolved by the bot labels bot-resolved`() {
        assertEquals(ClusterLabel.BOT_RESOLVED, label(botNote(1, "x", resolvedBy = BOT, anchor = same)))
    }

    @Test
    fun `untouched note stays unknown`() {
        assertEquals(ClusterLabel.UNKNOWN, label(botNote(1, "x", anchor = same)))
    }

    @Test
    fun `skin tone thumbs and check mark reactions count like plain thumbs`() {
        val award = { name: String -> listOf(CorpusAward(name, "bob")) }
        assertEquals(ClusterLabel.ACCEPTED, label(botNote(1, "x", awards = award("thumbsup_tone3"), anchor = same)))
        assertEquals(ClusterLabel.ACCEPTED, label(botNote(1, "x", awards = award("white_check_mark"), anchor = same)))
        assertEquals(ClusterLabel.REJECTED, label(botNote(1, "x", awards = award("thumbsdown_tone5"), anchor = same)))
        assertEquals(ClusterLabel.UNKNOWN, label(botNote(1, "x", awards = award("thumbsup_tone1") + down, anchor = same)))
        assertEquals(ClusterLabel.UNKNOWN, label(botNote(1, "x", awards = award("heavy_plus_sign"), anchor = same)))
    }
}
