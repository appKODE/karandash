package io.karandash.enrichment.jira

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AdfToMarkdownLayoutTest {
    private fun convert(json: String) = AdfToMarkdown.convert(Json.parseToJsonElement(json))

    private fun text(value: String) = """{"type":"text","text":"$value"}"""

    private fun para(vararg inline: String) = """{"type":"paragraph","content":[${inline.joinToString(",")}]}"""

    private fun item(vararg blocks: String) = """{"type":"listItem","content":[${blocks.joinToString(",")}]}"""

    private fun doc(vararg blocks: String) = """{"type":"doc","content":[${blocks.joinToString(",")}]}"""

    @Test
    fun `code block closing fence starts on its own line`() {
        val block = """{"type":"codeBlock","attrs":{"language":"kotlin"},"content":[${text("val x = 1")}]}"""
        val endsWithNewline = """{"type":"codeBlock","content":[{"type":"text","text":"y\n"}]}"""

        assertEquals("```kotlin\nval x = 1\n```", convert(doc(block)))
        assertEquals("```\ny\n```", convert(doc(endsWithNewline)))
    }

    @Test
    fun `nested lists and extra paragraphs start on their own lines`() {
        val nestedBullet = """{"type":"bulletList","content":[${item(para(text("sub")))}]}"""
        val nestedOrdered = """{"type":"orderedList","content":[${item(para(text("subn")))}]}"""
        val list =
            """{"type":"orderedList","content":[${item(
                para(text("one")),
                para(text("more")),
                nestedBullet,
                nestedOrdered
            )},${para(text("x"))}]}"""

        assertEquals("1. one\n   more\n  - sub\n  1. subn", convert(doc(list)))
    }

    @Test
    fun `full document renders headings marks inline nodes quotes and rules`() {
        val marks =
            """{"type":"text","text":"a","marks":[{"type":"em"},{"type":"code"},{"type":"strike"},{"type":"underline"},"x"]}"""
        val inline =
            listOf(
                marks,
                """{"type":"hardBreak"}""",
                """{"type":"emoji","attrs":{"text":":smile:"}}""",
                """{"type":"emoji"}""",
                """{"type":"status"}""",
                """{"type":"text"}""",
                """{"content":[]}""",
                """{"type":"text","text":"l","marks":[{"type":"link"}]}""",
            )
        val adf =
            doc(
                """{"type":"heading","attrs":{"level":9},"content":[${text("Big")}]}""",
                """{"type":"heading","content":[${text("Default")}]}""",
                para(*inline.toTypedArray()),
                """{"type":"blockquote","content":[${para(text("q1"))},${para(text("q2"))}]}""",
                """{"type":"rule"}""",
                """{"content":[]}""",
                """{"type":"panel"}""",
            )

        assertEquals(
            "###### Big\n# Default\n_`~~a~~`_\n:smile::emoji:[unsupported: status][l]()\n> q1\n> q2\n\n---\n[unsupported: panel]",
            convert(adf),
        )
    }
}
