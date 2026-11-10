package io.karandash.enrichment.confluence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageXmlToMarkdownTest {
    @Test
    fun `returns empty string for blank input`() {
        assertEquals("", StorageXmlToMarkdown.convert(""))
        assertEquals("", StorageXmlToMarkdown.convert("   "))
    }

    @Test
    fun `converts headings`() {
        val result = StorageXmlToMarkdown.convert("<h1>Title</h1><h2>Sub</h2>")
        assertTrue(result.contains("# Title"))
        assertTrue(result.contains("## Sub"))
    }

    @Test
    fun `converts paragraph`() {
        val result = StorageXmlToMarkdown.convert("<p>Hello world</p>")
        assertTrue(result.contains("Hello world"))
    }

    @Test
    fun `converts bold and italic inline marks`() {
        val result = StorageXmlToMarkdown.convert(
            "<p><strong>bold</strong> and <em>italic</em></p>"
        )
        assertTrue(result.contains("**bold**"))
        assertTrue(result.contains("_italic_"))
    }

    @Test
    fun `converts unordered list`() {
        val result = StorageXmlToMarkdown.convert("<ul><li>Item A</li><li>Item B</li></ul>")
        assertTrue(result.contains("- Item A"))
        assertTrue(result.contains("- Item B"))
    }

    @Test
    fun `converts code inline`() {
        val result = StorageXmlToMarkdown.convert("<p><code>val x = 1</code></p>")
        assertTrue(result.contains("`val x = 1`"))
    }

    @Test
    fun `converts pre block`() {
        val result = StorageXmlToMarkdown.convert("<pre>fun foo() {}</pre>")
        assertTrue(result.contains("```"))
        assertTrue(result.contains("fun foo() {}"))
    }

    @Test
    fun `renders macro as placeholder and skips macro content`() {
        val result = StorageXmlToMarkdown.convert(
            """<ac:structured-macro ac:name="code"><ac:plain-text-body>secret</ac:plain-text-body></ac:structured-macro>"""
        )
        assertTrue(result.contains("[macro: code]"))
        assertFalse(result.contains("secret"), "Macro body must be suppressed")
    }

    @Test
    fun `expands CDATA as raw text`() {
        val result = StorageXmlToMarkdown.convert("<pre><![CDATA[val x = 1 < 2]]></pre>")
        assertTrue(result.contains("val x = 1 < 2"))
    }

    @Test
    fun `ignores DOCTYPE declaration for XXE protection`() {
        val result = StorageXmlToMarkdown.convert(
            """<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><p>safe</p>"""
        )
        assertTrue(result.contains("safe"))
        assertFalse(result.contains("xxe"))
        assertFalse(result.contains("passwd"))
    }

    @Test
    fun `ignores XML comments`() {
        val result = StorageXmlToMarkdown.convert("<p><!-- comment -->text</p>")
        assertFalse(result.contains("comment"))
        assertTrue(result.contains("text"))
    }

    @Test
    fun `truncates input at MAX_INPUT_CHARS as billion-laughs defense`() {
        val huge = "<p>" + "A".repeat(StorageXmlToMarkdown.MAX_INPUT_CHARS + 10_000) + "</p>"
        val result = StorageXmlToMarkdown.convert(huge)
        assertTrue(result.length < StorageXmlToMarkdown.MAX_INPUT_CHARS)
    }

    @Test
    fun `decodes built-in XML entities`() {
        val result = StorageXmlToMarkdown.convert("<p>&amp; &lt; &gt; &quot; &apos;</p>")
        assertTrue(result.contains("& < > \" '"))
    }
}
