package io.karandash.plugins.outputs

import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.InlineFindingThread
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import io.karandash.utils.finding.AnchorFingerprint
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InlineReconcilerTest {
    @BeforeTest
    fun registerPolishPack() {
        PolishTranslationModule.register()
        RussianTranslationModule.register()
    }

    private val npe = finding("null-safety", "println(x!!)", "Avoid bang on x")
    private val naming = finding("naming", "val x = compute()", "Name x after what it holds")
    private val code = "val x = compute()\nprintln(x!!)\n"

    private fun finding(
        rule: String,
        lineText: String,
        message: String,
    ) = Finding(
        severity = Severity.WARNING,
        message = message,
        rule = rule,
        location = Location.FileRef("a.kt", 2),
        metadata = mapOf(WellKnownMetadataKeys.ANCHOR_FINGERPRINT to AnchorFingerprint.generate(rule, "a.kt", lineText)),
    )

    private fun comment(finding: Finding) = InlineCommentData("a.kt", 2, body = "${finding.message}\n${InlineFindingMarker.of(finding)}")

    private fun thread(
        finding: Finding,
        resolved: Boolean = false,
        replies: List<ReviewNote> = emptyList(),
        botBody: String = comment(finding).body,
    ): InlineFindingThread {
        val bot = ReviewNote("n-${finding.rule}", botBody, author = "bot")
        val thread = ReviewThread("t-${finding.rule}", listOf(bot) + replies, resolved, ReviewThreadPosition(newPath = "a.kt", newLine = 2))
        return checkNotNull(InlineFindingThread.of(thread))
    }

    @Test
    fun `a looks-fixed marker from a non-bot is not the bots reply`() {
        val forged = ReviewNote("r1", InlineFindingThread.LOOKS_FIXED_MARKER, author = "mallory")
        assertTrue(!InlineReconciler.hasLooksFixedReply(thread(npe, replies = listOf(forged))))
    }

    @Test
    fun `a looks-fixed marker from the bot is its reply`() {
        val genuine = ReviewNote("r1", InlineFindingThread.LOOKS_FIXED_MARKER, author = "bot")
        assertTrue(InlineReconciler.hasLooksFixedReply(thread(npe, replies = listOf(genuine))))
    }

    @Test
    fun `a matched comment is not new and an open match counts as open`() {
        val match = InlineReconciler.match(listOf(comment(npe), comment(naming)), listOf(thread(npe)))
        assertEquals(listOf(comment(naming)), match.unmatchedFindings)
        assertEquals(1, InlineReconciler.openCount(match))
    }

    @Test
    fun `a resolved thread still absorbs its finding`() {
        val match = InlineReconciler.match(listOf(comment(npe)), listOf(thread(npe, resolved = true)))
        assertTrue(match.unmatchedFindings.isEmpty())
        assertEquals(0, InlineReconciler.openCount(match))
    }

    @Test
    fun `a thread the bot resolved as fixed without a known resolver does not absorb a regression`() {
        val fixedNote = InlineReconcileStage.fixedBody(comment(npe).body, "abc")
        val match = InlineReconciler.match(listOf(comment(npe)), listOf(thread(npe, resolved = true, botBody = fixedNote)))
        assertEquals(listOf(comment(npe)), match.unmatchedFindings)
    }

    @Test
    fun `a thread marked fixed that a person answered still absorbs its finding`() {
        val fixedNote = InlineReconcileStage.fixedBody(comment(npe).body, "abc")
        val reply = ReviewNote("r1", "not fixed", author = "alice")
        val match = InlineReconciler.match(listOf(comment(npe)), listOf(thread(npe, true, listOf(reply), fixedNote)))
        assertTrue(match.unmatchedFindings.isEmpty())
    }

    @Test
    fun `an unmatched thread is fixed only when its anchor is gone at head`() {
        val open = thread(npe)
        assertTrue(InlineReconciler.isFixed(open, mapOf("a.kt" to "val x = compute()\nprintln(x ?: 0)\n")))
        assertTrue(!InlineReconciler.isFixed(open, mapOf("a.kt" to code)), "anchor still at head")
        assertTrue(!InlineReconciler.isFixed(open, emptyMap()), "no head content")
    }

    @Test
    fun `a thread whose refs lack fpv is never fixed`() {
        val bare = npe.copy(metadata = emptyMap())
        assertTrue(!InlineReconciler.isFixed(thread(bare), mapOf("a.kt" to "")))
    }

    @Test
    fun `the delta lists overflow in a collapsed block`() {
        val section = InlineReconciler.summarySection(openCount = 4, fixedCount = 2, overflow = listOf(comment(naming)))
        assertTrue("**Inline review:** 4 open, 2 fixed" in section, section)
        assertTrue("<details>" in section && "`a.kt:2`" in section && "naming" in section, section)
    }

    @Test
    fun `the delta without overflow has no details block`() {
        val section = InlineReconciler.summarySection(openCount = 0, fixedCount = 0, overflow = emptyList())
        assertTrue("<details>" !in section, section)
    }

    @Test
    fun `no inline activity gives no delta line`() {
        assertEquals("", InlineReconciler.summarySection(openCount = 0, fixedCount = 0, overflow = emptyList()))
        assertTrue("1 open, 0 fixed" in InlineReconciler.summarySection(1, 0, emptyList()))
    }

    @Test
    fun `the Russian delta and overflow list match the golden`() {
        val en = InlineReconciler.summarySection(4, 2, listOf(comment(naming)))
        val items = en.substringAfter("</summary>\n\n")

        val ru = InlineReconciler.summarySection(4, 2, listOf(comment(naming)), language = "ru-RU")

        assertEquals(
            "**Inline-ревью:** открытых — 4, исправлено — 2\n\n" +
                "<details><summary>Не опубликовано inline: 1</summary>\n\n$items",
            ru,
        )
    }

    @Test
    fun `the Polish delta uses the Polish strings and an unknown language the English ones`() {
        assertEquals("**Przegląd inline:** otwarte: 1, naprawione: 0", InlineReconciler.summarySection(1, 0, emptyList(), "pl"))
        assertEquals(InlineReconciler.summarySection(1, 0, emptyList()), InlineReconciler.summarySection(1, 0, emptyList(), "xx"))
    }

    @Test
    fun `the listing failed notice follows the language`() {
        assertTrue("could not be listed" in InlineReconciler.listingFailedNotice(null))
        assertTrue("не удалось получить список обсуждений" in InlineReconciler.listingFailedNotice("ru"))
        assertTrue("nie udało się pobrać listy wątków" in InlineReconciler.listingFailedNotice("pl_PL"))
    }
}
