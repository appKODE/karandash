package io.karandash.api.finding

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewCoverageTest {
    @Test
    fun `describe names the diff and context the budget left out`() {
        val coverage = ReviewCoverage(
            totalFiles = 3,
            reviewedFiles = 2,
            dropped = listOf("B.kt", "C.kt (2 of 5 hunks)"),
            contextDropped = listOf("ci-context"),
        )

        assertEquals(
            "reviewed 2/3 files, 0 chunks skipped, 0 truncated, left out: B.kt, C.kt (2 of 5 hunks), context left out: ci-context",
            coverage.describe(),
        )
    }

    @Test
    fun `describe names preprocessor exclusions apart from what the budget left out`() {
        val coverage = ReviewCoverage(totalFiles = 3, reviewedFiles = 1, dropped = listOf("B.kt"), excluded = listOf("gone.kt (deleted)"))

        assertEquals(
            "reviewed 1/3 files, 0 chunks skipped, 0 truncated, left out: B.kt, excluded: gone.kt (deleted)",
            coverage.describe(),
        )
    }

    @Test
    fun `describe without anything left out keeps its short form`() {
        assertEquals("reviewed 3/3 files, 0 chunks skipped, 0 truncated", ReviewCoverage(totalFiles = 3, reviewedFiles = 3).describe())
    }
}
