package io.karandash.models.template

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MustacheTest {
    private fun data(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun render(
        template: String,
        json: String = "{}",
        partials: Map<String, String> = emptyMap(),
    ): String = Mustache.render(template, data(json), partials)

    @Test
    fun `variables are escaped and triple or ampersand tags are raw`() {
        val json = """{"x": "<b>&</b>"}"""
        assertEquals("&lt;b&gt;&amp;&lt;/b&gt;", render("{{x}}", json))
        assertEquals("<b>&</b>", render("{{{x}}}", json))
        assertEquals("<b>&</b>", render("{{& x }}", json))
    }

    @Test
    fun `dotted names walk objects and fall back up the context stack`() {
        val json = """{"a": {"b": {"c": 1}}, "top": "T", "list": [{"n": "x"}]}"""
        assertEquals("1", render("{{a.b.c}}", json))
        assertEquals("", render("{{a.missing.c}}", json))
        assertEquals("xT", render("{{#list}}{{n}}{{top}}{{/list}}", json))
    }

    @Test
    fun `sections iterate lists push objects and skip falsy values`() {
        val json = """{"l": [1, 2, 3], "o": {"k": "v"}, "f": false, "e": [], "s": "", "n": null, "t": true}"""
        assertEquals("1,2,3,", render("{{#l}}{{.}},{{/l}}", json))
        assertEquals("v", render("{{#o}}{{k}}{{/o}}", json))
        assertEquals("", render("{{#f}}x{{/f}}{{#e}}x{{/e}}{{#s}}x{{/s}}{{#n}}x{{/n}}{{#missing}}x{{/missing}}", json))
        assertEquals("yes", render("{{#t}}yes{{/t}}", json))
        assertEquals("1", render("{{#z}}0{{/z}}{{#o}}1{{/o}}", """{"z": 0, "o": 1}"""))
        assertEquals("fesnm", render("{{^f}}f{{/f}}{{^e}}e{{/e}}{{^s}}s{{/s}}{{^n}}n{{/n}}{{^missing}}m{{/missing}}{{^t}}t{{/t}}", json))
    }

    @Test
    fun `standalone tags remove their whole line`() {
        val template = "a\n  {{#l}}\n  - {{.}}\n  {{/l}}\n{{! note }}\nb\n"
        assertEquals("a\n  - 1\n  - 2\nb\n", render(template, """{"l": [1, 2]}"""))
    }

    @Test
    fun `tags sharing a line with text are not standalone`() {
        assertEquals("x 1 y\n", render("x {{#l}}{{.}}{{/l}} y\n", """{"l": [1]}"""))
    }

    @Test
    fun `partials render with the current context and standalone indentation`() {
        val partials = mapOf("row" to "- {{n}}\n")
        assertEquals("  - a\n  - b\n", render("{{#l}}\n  {{>row}}\n{{/l}}\n", """{"l": [{"n": "a"}, {"n": "b"}]}""", partials))
        assertEquals("[]", render("[{{>missing}}]"))
    }

    @Test
    fun `recursive partials stop at the depth limit`() {
        assertFailsWith<IllegalArgumentException> { render("{{>self}}", partials = mapOf("self" to "{{>self}}")) }
    }

    @Test
    fun `escape lambdas apply to the rendered section body`() {
        val json = """{"t": "a|b <c> \"q\""}"""
        assertEquals("a\\|b &lt;c&gt; \"q\"", render("{{#md}}{{{t}}}{{/md}}", json))
        assertEquals("a|b &lt;c&gt; \"q\"", render("{{#slack}}{{{t}}}{{/slack}}", json))
        assertEquals("a|b <c> \\\"q\\\"", render("{{#json}}{{{t}}}{{/json}}", json))
    }

    @Test
    fun `a data key shadows a lambda of the same name`() {
        assertEquals("x", render("{{#md}}x{{/md}}", """{"md": true}"""))
        assertEquals("", render("{{#md}}x{{/md}}", """{"md": false}"""))
    }

    @Test
    fun `malformed templates are rejected`() {
        assertFailsWith<IllegalArgumentException> { Mustache.validate("{{#a}}x") }
        assertFailsWith<IllegalArgumentException> { Mustache.validate("{{/a}}") }
        assertFailsWith<IllegalArgumentException> { Mustache.validate("{{#a}}{{/b}}") }
        assertFailsWith<IllegalArgumentException> { Mustache.validate("{{x") }
        assertFailsWith<IllegalArgumentException> { Mustache.validate("{{=<% %>=}}") }
    }

    @Test
    fun `output over the cap fails`() {
        assertFailsWith<IllegalStateException> {
            Mustache.render("{{#l}}0123456789{{/l}}", data("""{"l": [1, 2, 3]}"""), maxOutputChars = 25)
        }
    }

    @Test
    fun `custom default escaper applies to double mustache only`() {
        val out = Mustache.render("{{x}} {{{x}}}", data("""{"x": "a_b"}"""), escape = TemplateEscapes::markdown)
        assertEquals("a\\_b a_b", out)
        assertEquals("a\\#1", TemplateEscapes.markdown("a#1"))
    }

    @Test
    fun `markdown escaper keeps a code span as code`() {
        assertEquals("e.g. `JIRA-0: feat` \\#1", TemplateEscapes.markdown("e.g. `JIRA-0: feat` #1"))
        assertEquals("`` a`b ``", TemplateEscapes.markdown("`` a`b ``"))
        assertEquals("`a\\|b` @\u200bx", TemplateEscapes.markdown("`a|b` @x"))
        assertEquals("\\` lone", TemplateEscapes.markdown("` lone"))
    }

    @Test
    fun `markdown escaper neutralises emphasis and entities in prose only`() {
        assertEquals("\\*\\*a\\*\\* \\_b\\_ \\~\\~c\\~\\~ &amp;lt;", TemplateEscapes.markdown("**a** _b_ ~~c~~ &lt;"))
        assertEquals("`*a_b*` \\*", TemplateEscapes.markdown("`*a_b*` *"))
    }

    @Test
    fun `a partial reused in a loop and at another indent renders each time`() {
        val partials = mapOf("p" to "[{{.}}]\n")
        val out = Mustache.render("{{#xs}}\n{{>p}}\n  {{>p}}\n{{/xs}}\n", data("""{"xs": ["a", "b"]}"""), partials)
        assertEquals("[a]\n  [a]\n[b]\n  [b]\n", out)
    }

    @Test
    fun `escapers by name accept the markdown alias and any case`() {
        assertEquals("a\\_b", TemplateEscapes.byName("Markdown")?.invoke("a_b"))
        assertEquals("a\\_b", TemplateEscapes.byName("md")?.invoke("a_b"))
        assertEquals("a_b", TemplateEscapes.byName("none")?.invoke("a_b"))
        assertEquals(null, TemplateEscapes.byName("rot13"))
    }
}
