package io.karandash.cli.pipeline

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffLineRangesTest {
    private fun mr(vararg changes: FileChange) =
        MergeRequest(
            id = "1",
            iid = 7,
            title = "t",
            description = null,
            author = User(username = "dev", name = "Dev"),
            sourceBranch = "feature",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = changes.toList(),
            commits = emptyList(),
        )

    @Test
    fun `a deletions-only file keeps an empty set so no inline comment targets it`() {
        val ranges = diffLineRanges(
            mr(
                FileChange("A.kt", ChangeType.MODIFIED, diffContent = "@@ -10,3 +12,4 @@\n ctx\n+added\n ctx2"),
                FileChange("D.kt", ChangeType.MODIFIED, diffContent = "@@ -1,2 +1,1 @@\n-gone\n kept"),
            ),
        )

        assertEquals(mapOf("A.kt" to setOf(13), "D.kt" to emptySet()), ranges)
    }

    @Test
    fun `a file without diff text stays unchecked`() {
        assertEquals(emptyMap(), diffLineRanges(mr(FileChange("Big.kt", ChangeType.MODIFIED))))
        assertEquals(emptyMap(), diffLineRanges(null))
    }
}
