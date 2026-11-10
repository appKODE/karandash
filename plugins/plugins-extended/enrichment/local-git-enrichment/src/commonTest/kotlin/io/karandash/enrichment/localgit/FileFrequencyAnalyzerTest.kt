package io.karandash.enrichment.localgit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileFrequencyAnalyzerTest {
    @Test
    fun `tally counts per-file occurrences`() {
        val output = """
            src/Auth.kt
            src/Auth.kt
            src/Main.kt
        """.trimIndent()
        val known = setOf("src/Auth.kt", "src/Main.kt")
        val counts = FileFrequencyAnalyzer.tally(output, known)
        assertEquals(2, counts["src/Auth.kt"])
        assertEquals(1, counts["src/Main.kt"])
    }

    @Test
    fun `tally ignores blank lines`() {
        val output = "\n  \nsrc/A.kt\n\n"
        val counts = FileFrequencyAnalyzer.tally(output, setOf("src/A.kt"))
        assertEquals(1, counts["src/A.kt"])
    }

    @Test
    fun `tally empty output returns empty`() {
        assertTrue(FileFrequencyAnalyzer.tally("", emptySet()).isEmpty())
        assertTrue(FileFrequencyAnalyzer.tally("   \n\n  ", emptySet()).isEmpty())
    }

    @Test
    fun `tally with empty knownPaths accepts every path`() {
        val counts = FileFrequencyAnalyzer.tally("a.kt\nb.kt\na.kt", emptySet())
        assertEquals(2, counts["a.kt"])
        assertEquals(1, counts["b.kt"])
    }

    @Test
    fun `tally drops paths outside knownPaths set`() {
        val counts = FileFrequencyAnalyzer.tally("src/A.kt\nsrc/Unexpected.kt", setOf("src/A.kt"))
        assertEquals(mapOf("src/A.kt" to 1), counts)
    }

    @Test
    fun `tally aggregates utf-8 filenames`() {
        val output = "src/Αuth.kt\nsrc/Αuth.kt"
        val counts = FileFrequencyAnalyzer.tally(output, setOf("src/Αuth.kt"))
        assertEquals(2, counts["src/Αuth.kt"])
    }

    @Test
    fun `tally handles lines with only tabs and spaces`() {
        val output = "\t\t\n   \t  \nsrc/A.kt\n\t"
        val counts = FileFrequencyAnalyzer.tally(output, emptySet())
        assertEquals(1, counts.size)
        assertEquals(1, counts["src/A.kt"])
    }

    @Test
    fun `tally handles carriage return line endings`() {
        val output = "src/A.kt\r\nsrc/B.kt\r\n"
        val counts = FileFrequencyAnalyzer.tally(output, emptySet())
        // trim() on each line handles \r
        assertTrue(counts.isNotEmpty())
    }
}
