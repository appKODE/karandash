package io.karandash.plugins.ai.core.metrics

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NcdCloneDetectorTest {
    private val author = User(username = "dev", name = "Dev")

    private fun makeMr(changes: List<FileChange>) =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = author,
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = changes,
        )

    private fun change(
        path: String,
        diff: String?
    ) = FileChange(
        path = path,
        changeType = ChangeType.ADDED,
        additions = diff?.lines()?.count { it.startsWith("+") } ?: 0,
        deletions = 0,
        diffContent = diff,
    )

    /** Generates a diff string of added lines totaling at least [size] characters. */
    private fun addedDiff(content: String): String = content.lines().joinToString("\n") { "+$it" }

    private val longContent = """
        fun processData(input: List<String>): List<String> {
            return input.filter { it.isNotBlank() }
                .map { it.trim() }
                .distinct()
                .sorted()
        }
        fun validateInput(data: String): Boolean {
            return data.isNotBlank() && data.length > 3
        }
    """.trimIndent()

    @Test
    fun `empty MR returns empty`() {
        val result = NcdCloneDetector.detect(makeMr(emptyList()))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single file returns empty — need pairs`() {
        val mr = makeMr(listOf(change("src/A.kt", addedDiff(longContent))))
        val result = NcdCloneDetector.detect(mr)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `identical content detected as clone — NCD near 0`() {
        val diff = addedDiff(longContent)
        val mr = makeMr(
            listOf(
                change("src/A.kt", diff),
                change("src/B.kt", diff),
            ),
        )
        val result = NcdCloneDetector.detect(mr, maxSimilarity = 0.5)
        assertEquals(1, result.size)
        val pair = result.first()
        assertTrue(pair.ncdScore < 0.5, "Identical content should have low NCD, got ${pair.ncdScore}")
    }

    @Test
    fun `completely different content not flagged`() {
        val diffA = addedDiff("a".repeat(100))
        val diffB = addedDiff("z".repeat(100))
        val mr = makeMr(
            listOf(
                change("src/A.kt", diffA),
                change("src/B.kt", diffB),
            ),
        )
        val result = NcdCloneDetector.detect(mr, maxSimilarity = 0.1)
        assertTrue(result.isEmpty(), "Very different content should not be flagged as clone")
    }

    @Test
    fun `computeNcd is symmetric`() {
        val x = "fun foo() { return 42 } fun bar() { return 99 } fun baz() { }"
        val y = "fun qux() { return 1 } fun quux() { return 2 } fun corge() { }"
        val ncdXY = NcdCloneDetector.computeNcd(x, y)
        val ncdYX = NcdCloneDetector.computeNcd(y, x)
        assertEquals(ncdXY, ncdYX, "NCD must be symmetric")
    }

    @Test
    fun `computeNcd empty string handling`() {
        val result = NcdCloneDetector.computeNcd("", "")
        assertEquals(0.0, result, "Identical empty strings should return 0.0")
    }

    @Test
    fun `min fragment size filters short diffs`() {
        // Under 50 chars after stripping diff prefix
        val shortDiff = "+short\n+code"
        val mr = makeMr(
            listOf(
                change("src/A.kt", shortDiff),
                change("src/B.kt", shortDiff),
            ),
        )
        val result = NcdCloneDetector.detect(mr)
        assertTrue(result.isEmpty(), "Fragments under MIN_FRAGMENT_SIZE should be filtered")
    }

    @Test
    fun `results sorted by NCD score ascending`() {
        val nearIdenticalDiff = addedDiff(longContent)
        val slightlyDifferent = addedDiff(longContent + "\nfun extra() { return 1 }")
        val verySimilar = addedDiff(longContent + "\nfun extra() { return 2 }")
        val mr = makeMr(
            listOf(
                change("src/A.kt", nearIdenticalDiff),
                change("src/B.kt", nearIdenticalDiff),
                change("src/C.kt", slightlyDifferent),
                change("src/D.kt", verySimilar),
            ),
        )
        val result = NcdCloneDetector.detect(mr, maxSimilarity = 1.0)
        for (i in 0 until result.size - 1) {
            assertTrue(
                result[i].ncdScore <= result[i + 1].ncdScore,
                "Results must be sorted ascending by NCD score",
            )
        }
    }

    @Test
    fun `approximateCompressedSize is zero for empty string`() {
        assertEquals(0.0, NcdCloneDetector.approximateCompressedSize(""))
    }

    @Test
    fun `approximateCompressedSize is positive for non-empty string`() {
        assertTrue(NcdCloneDetector.approximateCompressedSize("hello world") > 0.0)
    }
}
