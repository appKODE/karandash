package io.karandash.cli.commands.stats.audit

import io.karandash.cli.commands.stats.audit.AuditFixtures.anchor
import io.karandash.cli.commands.stats.audit.AuditFixtures.botNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnchorCheckTest {
    private val code = listOf("fun a() {", "    val x = 1", "", "    val y = load()", "    // note", "    use(y)", "}")

    @Test
    fun `quoted code near the anchor is accurate`() {
        val result = AnchorCheck.check(botNote(1, "`load()` may return null", line = 10, anchor = anchor(code)))
        assertEquals(AnchorResult(quotesCode = true, accurate = true, nonCodeLine = false), result)
    }

    @Test
    fun `quoted code missing from the window is inaccurate`() {
        val result = AnchorCheck.check(botNote(1, "`save()` may fail", line = 10, anchor = anchor(code)))
        assertEquals(AnchorResult(quotesCode = true, accurate = false, nonCodeLine = false), result)
    }

    @Test
    fun `anchor on blank brace or comment line is non-code`() {
        assertEquals(true, AnchorCheck.check(botNote(1, "x", line = 9, anchor = anchor(code)))?.nonCodeLine)
        assertEquals(true, AnchorCheck.check(botNote(1, "x", line = 11, anchor = anchor(code)))?.nonCodeLine)
        assertEquals(true, AnchorCheck.check(botNote(1, "`load()`", line = 13, anchor = anchor(code)))?.nonCodeLine)
        assertEquals(false, AnchorCheck.check(botNote(1, "`load()`", line = 13, anchor = anchor(code)))?.accurate)
    }

    @Test
    fun `note without anchor is not checked`() {
        assertNull(AnchorCheck.check(botNote(1, "x", path = null)))
    }

    @Test
    fun `code line classification`() {
        assertTrue(AnchorCheck.isCodeLine("val x = 1"))
        assertFalse(AnchorCheck.isCodeLine("  });"))
        assertFalse(AnchorCheck.isCodeLine("# comment"))
        assertFalse(AnchorCheck.isCodeLine(" * kdoc"))
    }

    @Test
    fun `quoted code from inline span or fence`() {
        assertEquals("load()", AnchorCheck.quotedCode("may `load()` fail"))
        assertEquals("val y = load()", AnchorCheck.quotedCode("see\n```kotlin\nval y = load()\n```"))
        assertNull(AnchorCheck.quotedCode("plain prose"))
    }

    @Test
    fun `rule id in the karandash header is not quoted code`() {
        val body = "<!-- karandash-inline -->\n**⚠️ WARNING** — `review-comments`\n\n`load()` may return null"
        assertEquals("load()", AnchorCheck.quotedCode(body))
        assertNull(AnchorCheck.quotedCode("<!-- karandash-inline -->\n**ℹ️ INFO** — `review-comments`\n\nplain prose"))
        val result = AnchorCheck.check(botNote(1, body, line = 10, anchor = anchor(code)))
        assertEquals(AnchorResult(quotesCode = true, accurate = true, nonCodeLine = false), result)
    }

    @Test
    fun `code quoted in a terse header title is quoted code while the rule id is not`() {
        val terse = "**⚠️ WARNING** — `review-comments` · `load()` returns null for deleted users"
        assertEquals("load()", AnchorCheck.quotedCode(terse))
        assertNull(AnchorCheck.quotedCode("**ℹ️ INFO** — `review-comments` · plain prose"))
    }

    @Test
    fun `backtick span after an em dash in prose is still quoted code`() {
        assertEquals("b()", AnchorCheck.quotedCode("prefer this — `b()` over a"))
    }
}
