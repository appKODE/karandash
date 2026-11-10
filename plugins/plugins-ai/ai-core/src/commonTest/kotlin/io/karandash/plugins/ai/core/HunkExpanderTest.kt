package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.rules.HunkExpansionConfig
import io.karandash.utils.mr.HunkParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HunkExpanderTest {
    private val content = listOf(
        "fun a() {",
        "    val x = 1",
        "    val y = 2",
        "}",
        "",
        "fun b() {",
        "    val z = 3",
        "    return",
        "}",
        "// end",
    ).joinToString("\n", postfix = "\n")

    private val diff = "@@ -7 +7 @@\n-    val z = 0\n+    val z = 3"

    private fun expanded(config: HunkExpansionConfig): String? =
        HunkExpander.expand(FileChange("B.kt", ChangeType.MODIFIED, 1, 1, diff, content), config).diffContent

    @Test
    fun `a small file is sent whole with its changed line numbers unchanged`() {
        val out = checkNotNull(expanded(HunkExpansionConfig(enabled = true)))

        assertTrue(out.startsWith("@@ -1,10 +1,10 @@\n fun a() {\n"), out)
        assertTrue(out.endsWith("\n }\n // end"), out)
        assertEquals(setOf(7), HunkParser.extractAddedLineNumbers(out))
        assertTrue("L7(+)     val z = 3" in HunkParser.annotateAddedLinesWithNumbers(out), out)
    }

    @Test
    fun `a big file gets the hunk widened to its enclosing block within the cap`() {
        val block = expanded(HunkExpansionConfig(enabled = true, wholeFileMaxLines = 0))
        val capped = expanded(HunkExpansionConfig(enabled = true, wholeFileMaxLines = 0, maxHunkLines = 2))

        assertEquals("@@ -6,4 +6,4 @@\n fun b() {\n-    val z = 0\n+    val z = 3\n     return\n }", block)
        assertEquals("@@ -7,2 +7,2 @@\n-    val z = 0\n+    val z = 3\n     return", capped)
    }

    @Test
    fun `a diff that does not match the head content or a disabled config is left as it is`() {
        val stale = FileChange("B.kt", ChangeType.MODIFIED, 1, 1, diff, content.replace("val z = 3", "val z = 4"))
        val off = FileChange("B.kt", ChangeType.MODIFIED, 1, 1, diff, content)

        assertSame(stale, HunkExpander.expand(stale, HunkExpansionConfig(enabled = true)))
        assertSame(off, HunkExpander.expand(off, HunkExpansionConfig()))
    }
}
