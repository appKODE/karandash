package io.karandash.plugins.formats

import io.karandash.api.finding.InlineCommentData
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A suggested-code block that holds only comments or prose is not rendered as code. */
class MarkdownSuggestedCodeTest {
    private fun bodyFor(code: String) =
        formatInlineCommentBody(
            Finding(
                severity = Severity.WARNING,
                message = "msg",
                rule = "r",
                location = Location.FileRef(path = "src/A.kt", line = 3),
                suggestion = "Handle the null case",
                metadata = mapOf(WellKnownMetadataKeys.SUGGESTED_CODE to code),
            ),
        )

    @Test
    fun `comment only suggested code is dropped and the suggestion text shown`() {
        val body = bodyFor("// TODO: handle null here\n// and log it")

        assertFalse(InlineCommentData.SUGGESTION_START in body, body)
        assertFalse("```" in body, body)
        assertTrue("**How to fix:** Handle the null case" in body, body)
    }

    @Test
    fun `prose suggested code is dropped`() {
        val body = bodyFor("Consider extracting this logic into a separate helper function.")

        assertFalse("```" in body, body)
        assertTrue("**How to fix:** Handle the null case" in body, body)
    }

    @Test
    fun `real code with a comment line survives`() {
        val body = bodyFor("// guard\nval name = user?.name ?: return")

        assertTrue("```diff\n+ // guard\n+ val name = user?.name ?: return\n```" in body, body)
    }

    @Test
    fun `short code without punctuation survives`() {
        val body = bodyFor("return null")

        assertTrue("+ return null" in body, body)
    }

    @Test
    fun `preprocessor and attribute lines are code not comments`() {
        val body = bodyFor("#include <stdio.h>")

        assertTrue("+ #include <stdio.h>" in body, body)
    }
}
