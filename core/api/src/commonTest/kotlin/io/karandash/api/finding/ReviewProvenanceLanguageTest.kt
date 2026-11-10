package io.karandash.api.finding

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewProvenanceLanguageTest {
    @BeforeTest
    fun registerPack() = TestZzPack.register()

    @Test
    fun `the AI status line follows the language with its skip reason translated`() {
        val provenance =
            ReviewProvenance(
                aiStatus = "AI: not run — gated: lockfile",
                aiStatusTemplate = "AI: not run — %1",
                aiStatusArgs = listOf("gated: lockfile"),
            )

        assertEquals("AI-OFF[GATED[LOCK]]", provenance.aiStatusIn(LANGUAGE))
        assertEquals("AI: not run — gated: lockfile", provenance.aiStatusIn("en"))
    }

    @Test
    fun `a status line without a template stays as set`() {
        assertEquals("AI: custom", ReviewProvenance(aiStatus = "AI: custom").aiStatusIn(LANGUAGE))
        assertNull(ReviewProvenance().aiStatusIn(LANGUAGE))
    }

    @Test
    fun `coverage counts and hunk names follow the plural rule of the pack`() {
        val coverage =
            ReviewCoverage(
                totalFiles = 3,
                reviewedFiles = 2,
                chunksSkipped = 2,
                truncatedFiles = 1,
                dropped = listOf("a.kt (1 of 5 hunks)", "b.kt (2 of 21 hunks)", "c.kt (deleted)", "d.kt (x of 2 hunks)"),
            )

        assertEquals(
            "REVIEWED[2/3], CHUNKS2[2], TRUNC[1], OUT[a.kt H5[1/5], b.kt H1[2/21], c.kt (deleted), d.kt (x of 2 hunks)]",
            coverage.describe(LANGUAGE),
        )
        assertEquals(
            "reviewed 2/3 files, 2 chunks skipped, 1 truncated, left out: a.kt (1 of 5 hunks), b.kt (2 of 21 hunks), " +
                "c.kt (deleted), d.kt (x of 2 hunks)",
            coverage.describe("en"),
        )
    }

    @Test
    fun `more than ten left-out names are cut with a count`() {
        val coverage = ReviewCoverage(totalFiles = 12, reviewedFiles = 0, dropped = (1..12).map { "f$it.kt" })

        assertEquals(
            "REVIEWED[0/12], CHUNKS5[0], TRUNC[0], OUT[${(1..10).joinToString(", ") { "f$it.kt" }} +2]",
            coverage.describe(LANGUAGE),
        )
    }

    private companion object {
        const val LANGUAGE = TestZzPack.LANGUAGE
    }
}
