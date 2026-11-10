package io.karandash.plugins.formats

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertTrue

/** A literal backslash-n in finding text becomes a newline in inline comments, except inside code. */
class MarkdownEscapedNewlineTest {
    private fun body(
        message: String,
        explanation: String? = null,
    ) = formatInlineCommentBody(
        Finding(
            severity = Severity.WARNING,
            message = message,
            rule = "r",
            location = Location.FileRef(path = "src/A.kt", line = 3),
            explanation = explanation,
        ),
    )

    @Test
    fun `literal backslash n in prose becomes a newline`() {
        val b = body("First point.\\nSecond point.", explanation = "why\\nmore")

        assertTrue(" · First point.\n\nSecond point." in b, b)
        assertTrue("ℹ️ why more" in b, b)
        assertTrue("\\n" !in b.substringBefore("<!--"), b)
    }

    @Test
    fun `literal backslash n inside a code span is kept and a code block is dropped`() {
        val b = body("Use `println(\"a\\nb\")` here.\\nThen it fails:\n```kotlin\nval s = \"x\\ny\"\n```\nfor every user.")

        assertTrue("`println(\"a\\nb\")`" in b, b)
        assertTrue("here.\n\nThen it fails: for every user." in b, b)
        assertTrue("val s" !in b.substringBefore("<!--"), b)
    }
}
