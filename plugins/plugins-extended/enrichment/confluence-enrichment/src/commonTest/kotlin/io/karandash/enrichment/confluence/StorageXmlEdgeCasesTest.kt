package io.karandash.enrichment.confluence

import kotlin.test.Test
import kotlin.test.assertEquals

class StorageXmlEdgeCasesTest {
    @Test
    fun `escaped entity text is decoded only once`() {
        assertEquals("write &lt;br&gt; & go", StorageXmlToMarkdown.convert("<p>write &amp;lt;br&amp;gt; &amp; go</p>"))
    }

    @Test
    fun `headings inline marks code and line breaks render as markdown`() {
        val xml =
            "<h1>A</h1><h2>B</h2><h3>C</h3><h4>D</h4><h5>E</h5><h6>F</h6>" +
                "<p><b>bold</b> <i>it</i> <code>x</code><br/>next</p><pre><code>val y</code></pre>"

        assertEquals(
            "# A\n## B\n### C\n#### D\n##### E\n###### F\n**bold** _it_ `x`\nnext\n```\nval y\n```",
            StorageXmlToMarkdown.convert(xml),
        )
    }

    @Test
    fun `comments declarations and cdata are handled and unterminated markup is dropped`() {
        assertEquals("a  b  raw", StorageXmlToMarkdown.convert("<p>a <!-- note --> b <!DOCTYPE x> <![CDATA[raw]]></p>"))
        assertEquals("a", StorageXmlToMarkdown.convert("<p>a</p><!-- open"))
        assertEquals("tail", StorageXmlToMarkdown.convert("<![CDATA[tail"))
        assertEquals("a", StorageXmlToMarkdown.convert("a<!DOCTYPE"))
        assertEquals("a", StorageXmlToMarkdown.convert("a</p"))
        assertEquals("a", StorageXmlToMarkdown.convert("a<p class='x'"))
        assertEquals("a", StorageXmlToMarkdown.convert("a</><>"))
        assertEquals("", StorageXmlToMarkdown.convert("   "))
    }

    @Test
    fun `nested macros are skipped as a whole`() {
        val xml =
            "<ac:structured-macro ac:name=\"info\"><ac:rich-text-body><p>hidden</p></ac:rich-text-body>" +
                "</ac:structured-macro><ac:structured-macro></ac:structured-macro><p>after</p>"

        assertEquals("[macro: info]\n[macro: macro]\nafter", StorageXmlToMarkdown.convert(xml))
    }

    @Test
    fun `links keep their href as markdown links`() {
        val xml = "<p>See <a href=\"https://ex.com/a?x=1&amp;y=2\">the <strong>spec</strong></a> now</p>"

        assertEquals("See [the **spec**](https://ex.com/a?x=1&y=2) now", StorageXmlToMarkdown.convert(xml))
    }

    @Test
    fun `anchor without href renders its text only`() {
        assertEquals("plain text", StorageXmlToMarkdown.convert("<p>plain <a name=\"top\">text</a></p>"))
    }

    @Test
    fun `ordered lists are numbered per list and bullet lists stay bullets`() {
        val xml = "<ol><li>A</li><li>B</li></ol><ul><li>D</li></ul><ol><li>E</li></ol>"

        assertEquals("1. A\n2. B\n- D\n1. E", StorageXmlToMarkdown.convert(xml))
    }
}
