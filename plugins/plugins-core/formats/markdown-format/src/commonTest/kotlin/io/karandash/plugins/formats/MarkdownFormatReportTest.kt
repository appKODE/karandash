package io.karandash.plugins.formats

import kotlin.test.Test
import kotlin.test.assertEquals

/** user text cannot ping, link an issue or swallow the escape character. */
class MarkdownFormatReportTest {
    @Test
    fun `at sign is defused so it cannot mention`() {
        assertEquals("ping @​team", sanitizeMarkdown("ping @team"))
    }

    @Test
    fun `hash is escaped so it cannot link an issue`() {
        assertEquals("see \\#42", sanitizeMarkdown("see #42"))
    }

    @Test
    fun `backslash is escaped first so it cannot unescape the next char`() {
        assertEquals("a\\\\\\`b", sanitizeMarkdown("a\\`b"))
    }
}
