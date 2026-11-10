package io.karandash.models.finding

import io.karandash.models.template.Mustache
import io.karandash.models.template.TemplateEscapes
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SeverityAndTemplateGapsTest {
    @Test
    fun `severity maps to emoji tag and ansi colour`() {
        assertEquals("❌", SeverityFormat.toEmoji(Severity.ERROR))
        assertEquals("⚠️", SeverityFormat.toEmoji(Severity.WARNING))
        assertEquals("ℹ️", SeverityFormat.toEmoji(Severity.INFO))
        assertEquals("error", SeverityFormat.toTag(Severity.ERROR))
        assertEquals("warn", SeverityFormat.toTag(Severity.WARNING))
        assertEquals("info", SeverityFormat.toTag(Severity.INFO))
        assertEquals("\u001b[31m", SeverityFormat.toAnsiColor(Severity.ERROR))
        assertEquals("\u001b[33m", SeverityFormat.toAnsiColor(Severity.WARNING))
    }

    @Test
    fun `plain text escaping leaves text alone`() {
        assertEquals("<a> & b", TemplateEscapes.none("<a> & b"))
    }

    @Test
    fun `sections come out ordered and titled`() {
        fun f(
            section: String?,
            order: String? = null,
            title: String? = null,
        ) = Finding(Severity.INFO, "m", "r").copy(
            metadata = listOfNotNull(
                section?.let { PresentationKeys.SECTION to it },
                order?.let { PresentationKeys.SECTION_ORDER to it },
                title?.let { PresentationKeys.SECTION_TITLE to it },
            ).toMap(),
        )
        val sections = PresentationKeys.sections(listOf(f("b", "2"), f("a", "1", "Title A"), f("c"), f(null)))
        assertEquals(listOf("Title A", "b", "c"), sections.map { it.first })
    }

    @Test
    fun `templates render scalars and structures in sections`() {
        fun render(
            template: String,
            json: String,
        ) = Mustache.render(template, Json.parseToJsonElement(json), emptyMap())
        assertEquals("{&quot;a&quot;:1}", render("{{o}}", """{"o": {"a": 1}}"""))
        assertEquals("", render("{{n}}", """{"n": null}"""))
        assertEquals("[1,2]", render("{{l}}", """{"l": [1, 2]}"""))
        assertEquals("yes", render("{{#s}}yes{{/s}}", """{"s": "text"}"""))
        assertEquals("", render("{{#z}}yes{{/z}}", """{"z": 0}"""))
        assertEquals("x", render("{{#o}}x{{/o}}", """{"o": {"a": 1}}"""))
        assertEquals("  a\n  b", Mustache.render("  {{>p}}", Json.parseToJsonElement("{}"), mapOf("p" to "a\nb")))
        assertFailsWith<IllegalArgumentException> { Mustache.validate("{{=<% %>=}}") }
        assertFailsWith<IllegalArgumentException> { Mustache.validate("{{}}") }
    }
}
