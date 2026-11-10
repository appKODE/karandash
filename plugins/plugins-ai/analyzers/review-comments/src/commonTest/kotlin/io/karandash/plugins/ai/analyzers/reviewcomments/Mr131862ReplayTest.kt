package io.karandash.plugins.ai.analyzers.reviewcomments

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Seed reconstructed from a real incident (MR 131862).
 *
 * In that MR an AI review comment about `storeId = ProductId(book.storeId)` landed
 * on the unrelated `coverUrl = book.coverUrl,` line because the LLM returned
 * `line=308, endLine=335` - a 27-line span - for a single-line bug inside a
 * constructor call.
 *
 * These tests pin the new behaviour:
 * - A wide, implausible `endLine` (far beyond any added line) is dropped to null
 *   rather than silently relocated by several lines.
 * - When the LLM names a specific added line, the comment pins there exactly.
 * - When the LLM names a line outside the added-line set by more than the
 *   tightened snap radius, the whole comment is dropped.
 */
class Mr131862ReplayTest {
    private val module = ReviewCommentsAnalyzerModule()

    private fun mr(diff: String) =
        MergeRequest(
            id = "131862",
            iid = 131862,
            title = "Add Google Billing purchase logic",
            description = null,
            sourceBranch = "feat/google-billing",
            targetBranch = "master",
            author = User(username = "dev", name = "Dev"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = listOf(
                FileChange(
                    path = BOOK_VIEW_MODEL_PATH,
                    changeType = ChangeType.MODIFIED,
                    additions = 5,
                    deletions = 1,
                    diffContent = diff,
                ),
            ),
        )

    // Modelled on the real MR diff: a copy/constructor call gains five new argument lines
    // inside the @@ -300,10 +300,15 @@ hunk. The `@@ +300,15 @@` side means the
    // new-file numbering starts at 300, so after 4 context lines the added lines are
    // 304..308. The "bug" line is 308 - `storeId = ProductId(book.storeId)`. `line=307`
    // is the adjacent `coverUrl` argument, which is exactly where the real comment landed.
    private val bookViewModelDiff = """
        @@ -300,10 +300,15 @@
             val event = BookEvent(
                 id = book.id,
                 name = book.name,
                 description = book.description,
        +        author = book.author,
        +        category = book.category,
        +        publishedAt = book.publishedAt,
        +        coverUrl = book.coverUrl,
        +        storeId = ProductId(book.storeId),
                 createdAt = now,
             )
    """.trimIndent()

    private fun commentJson(
        line: Int,
        endLine: Int? = null
    ): String {
        val endPart = if (endLine != null) """, "endLine": $endLine""" else ""
        return """
            {
              "comments": [
                {
                  "file": "$BOOK_VIEW_MODEL_PATH",
                  "line": $line$endPart,
                  "category": "bug_risk",
                  "severity": "warning",
                  "confidence": 0.9,
                  "message": "Unsafe null assertion when constructing ProductId.",
                  "existingCode": "storeId = ProductId(book.storeId)",
                  "suggestedCode": "storeId = book.storeId?.let { ProductId(it) }"
                }
              ]
            }
            """.trimIndent()
    }

    @Test
    fun wide_endLine_span_is_snapped_to_null_not_posted_as_range() {
        // Reproduces the MR 131862 shape: LLM returns a 27-line endLine far beyond
        // any added line. Under the old default maxSnap=5 the endLine could have
        // snapped onto an unrelated neighbour row. Now it must null out rather
        // than silently relocate the `endLine` by 20+ rows.
        val findings = module.parseResponse(
            commentJson(line = 308, endLine = 335),
            "moonshotai/kimi-k2.5-0127",
            0,
            mr(bookViewModelDiff)
        )
        // line=308 is an actual added line (the storeId row) → Finding is kept.
        assertEquals(2, findings.size) // review + code-suggestion
        val loc = findings[0].location as Location.FileRef
        assertEquals(308, loc.line)
        assertNull(loc.endLine, "endLine=335 is 27 rows past the last added line — must not snap")
    }

    @Test
    fun wrong_line_beyond_snap_radius_demotes_to_file_level() {
        // If the LLM returned a line that's off by more than the tightened snap
        // radius (default 2), the comment is demoted to file level - we refuse to
        // silently relocate it onto an unrelated hunk-neighbour row.
        val findings = module.parseResponse(commentJson(line = 320), "moonshotai/kimi-k2.5-0127", 0, mr(bookViewModelDiff))
        assertEquals(1, findings.size, "LLM line 320 is 12 lines past last added line; must be demoted")
        assertEquals(Location.FileRef(path = BOOK_VIEW_MODEL_PATH), findings[0].location)
    }

    @Test
    fun exact_added_line_pins_comment_at_that_line() {
        // When the LLM picks the actual buggy line (308 - the storeId row), we pin
        // the Finding there exactly. This is the happy path once the stronger
        // prompt + `L<n>(+)` line-number tag steer the LLM away from the
        // first-line-of-block bias that produced MR 131862's mis-placement.
        val findings = module.parseResponse(commentJson(line = 308), "moonshotai/kimi-k2.5-0127", 0, mr(bookViewModelDiff))
        assertEquals(2, findings.size)
        val loc = findings[0].location as Location.FileRef
        assertEquals(308, loc.line)
    }

    companion object {
        private const val BOOK_VIEW_MODEL_PATH =
            "feature/book/ui/src/main/kotlin/com/example/app/feature/book/ui/screen/book/BookViewModel.kt"
    }
}
