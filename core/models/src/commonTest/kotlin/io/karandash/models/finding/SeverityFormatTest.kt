package io.karandash.models.finding

import kotlin.test.Test
import kotlin.test.assertEquals

class SeverityFormatTest {
    @Test
    fun `each severity maps to its format specific token`() {
        val expected = mapOf(
            Severity.ERROR to listOf("error", "ERROR", "error", "\u001b[31m"),
            Severity.WARNING to listOf("warning", "WARNING", "warning", "\u001b[33m"),
            Severity.INFO to listOf("note", "INFO", "info", "\u001b[36m"),
        )
        val actual = Severity.entries.associateWith {
            listOf(SeverityFormat.toSarif(it), SeverityFormat.toReviewdog(it), SeverityFormat.toJson(it), SeverityFormat.toAnsiColor(it))
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `severity parses case insensitively with a fallback`() {
        assertEquals(Severity.WARNING, Severity.fromString("Warning"))
        assertEquals(Severity.INFO, Severity.fromString("nope"))
        assertEquals(Severity.ERROR, Severity.fromString("nope", Severity.ERROR))
    }

    @Test
    fun `presentation sections group by section ordered by the smallest order`() {
        fun finding(
            rule: String,
            vararg meta: Pair<String, String>,
        ) = Finding(Severity.INFO, rule, rule, metadata = meta.toMap())
        val a1 = finding("a1", PresentationKeys.SECTION to "a", PresentationKeys.SECTION_ORDER to "5")
        val a2 = finding("a2", PresentationKeys.SECTION to "a", PresentationKeys.SECTION_TITLE to "Alpha")
        val b = finding("b", PresentationKeys.SECTION to "b", PresentationKeys.SECTION_ORDER to "1")
        val c = finding("c", PresentationKeys.SECTION to "c", PresentationKeys.SECTION_ORDER to "x")
        val none = finding("none")

        assertEquals(
            listOf("b" to listOf(b), "Alpha" to listOf(a1, a2), "c" to listOf(c)),
            PresentationKeys.sections(listOf(none, a1, c, b, a2)),
        )
    }

    @Test
    fun `severity extensions count and group`() {
        val findings = listOf(
            Finding(Severity.ERROR, "m", "r1"),
            Finding(Severity.WARNING, "m", "r1"),
            Finding(Severity.ERROR, "m", "r2"),
        )

        assertEquals(2, findings.errorsCount())
        assertEquals(true, findings.hasErrors())
        assertEquals(false, findings.drop(1).take(1).hasErrors())
        assertEquals(mapOf(Severity.ERROR to 2, Severity.WARNING to 1), findings.bySeverity().mapValues { it.value.size })
        assertEquals(mapOf("r1" to 2, "r2" to 1), findings.groupedByRule().mapValues { it.value.size })
    }
}
