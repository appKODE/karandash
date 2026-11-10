package io.karandash.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Generated rule hover must not shadow the example of the bundled docs. */
class HoverMergeTest {
    private val generated = "# branchNaming\n\nd\n\n## Properties\n- `pattern` (string)"

    @Test
    fun `generated hover gets the fallback example appended`() {
        val merged = mergeWithFallbackExample(generated, CoreValidatorDocs.DOCS.getValue("branchNaming"))
        assertTrue(merged.startsWith(generated), merged)
        assertTrue("## Example\n```\nbranchNaming {" in merged, merged)
        assertTrue("allowedPatterns = listOf(" in merged, merged)
    }

    @Test
    fun `without a fallback or example the generated hover stays as is`() {
        assertEquals(generated, mergeWithFallbackExample(generated, null))
        assertEquals(generated, mergeWithFallbackExample(generated, "# x\n\n## Properties\n- a"))
    }

    @Test
    fun `a generated hover that has its own example is not doubled`() {
        val own = "$generated\n\n## Example\nmine"
        assertEquals(own, mergeWithFallbackExample(own, CoreValidatorDocs.DOCS.getValue("branchNaming")))
    }
}
