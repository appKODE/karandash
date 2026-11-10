package io.karandash.plugins.outputs

import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.InlineFindingRef
import io.karandash.models.support.KarandashJson
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SummaryRowsTest {
    @BeforeTest
    fun registerPolishPack() {
        PolishTranslationModule.register()
        RussianTranslationModule.register()
    }

    private fun inline(fp: String) =
        InlineCommentData(
            "A.kt",
            1,
            body = "x\n" + InlineFindingMarker.PREFIX +
                KarandashJson.Default.encodeToString(InlineFindingRef.serializer(), InlineFindingRef("r", "A.kt", 0, fp)) + " -->",
        )

    private fun row(fp: String) = "| a | A.kt:1 | m | f ${InlineFindingMarker.ROW_PREFIX}$fp --> |"

    private val head = "| | Where | Finding | Fix |\n|---|---|---|---|"

    @Test
    fun `a table whose rows are all inline becomes the count line`() {
        val body = "# R\n\n$head\n${row("a")}\n${row("b")}\nmore below"

        assertEquals(
            "# R\n\n_+2 — в inline-комментариях_\n\nmore below",
            SummaryRows.withoutInline(body, listOf(inline("a"), inline("b")), "ru"),
        )
    }

    @Test
    fun `one inline comment drops one row of a repeated finding`() {
        val body = "$head\n${row("a")}\n${row("a")}"

        assertEquals("$head\n${row("a")}\n\n_+1 in inline comments_", SummaryRows.withoutInline(body, listOf(inline("a")), null))
    }

    @Test
    fun `the Where column goes when every row left says only MR`() {
        val body = "$head\n${row("a")}\n| ❌ | MR | Title too short | Fix it |"

        assertEquals(
            "| | Finding | Fix |\n|---|---|---|\n| ❌ | Title too short | Fix it |\n\n_+1 in inline comments_",
            SummaryRows.withoutInline(body, listOf(inline("a")), null),
        )
    }

    @Test
    fun `body without a covered row is returned unchanged`() {
        val body = "$head\n${row("a")}"

        assertEquals(body, SummaryRows.withoutInline(body, listOf(inline("z"), inline("")), null))
        assertEquals(body, SummaryRows.withoutInline(body, emptyList(), null))
    }

    @Test
    fun `updated stamp is localized and taken back out of an inline note`() {
        val stamp = UpdatedStamp(FixedClock(Instant.parse("2026-01-02T03:04:59Z")), "pl")
        val note = stamp.inline("body\n")

        assertEquals("body\n\n<sub>zaktualizowano 2026-01-02 03:04 UTC</sub>", note)
        assertEquals("body", UpdatedStamp.withoutStamp(note))
        assertEquals(note, stamp.inline(note))
        assertEquals("# no footer", stamp.summary("# no footer"))
    }
}
