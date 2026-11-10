package io.karandash.cli.pipeline

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MrViewTest {
    private val gitLab = mapOf(
        "CI_MERGE_REQUEST_PROJECT_URL" to "https://gitlab.example.com/g/p",
        "CI_MERGE_REQUEST_IID" to "31",
    )
    private val gitHub = mapOf(
        "GITHUB_SERVER_URL" to "https://github.com",
        "GITHUB_REPOSITORY" to "o/r",
        "GITHUB_REF" to "refs/pull/7/merge",
    )

    @Test
    fun `GitLab pipeline links its own merge request`() {
        val view = mrView(31, "t", gitLab::get)
        assertEquals("!31", view.ref)
        assertEquals("https://gitlab.example.com/g/p/-/merge_requests/31", view.url)
    }

    @Test
    fun `GitLab pipeline does not link another merge request`() {
        val view = mrView(1, "t", gitLab::get)
        assertEquals("#1", view.ref)
        assertNull(view.url)
    }

    @Test
    fun `GitHub Actions links only its own pull request`() {
        assertEquals("https://github.com/o/r/pull/7", mrView(7, "t", gitHub::get).url)
        assertNull(mrView(8, "t", gitHub::get).url)
    }

    @Test
    fun `no CI environment means no link`() {
        assertNull(mrView(1, "t") { null }.url)
    }

    private val changed = MergeRequest(
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
        changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED, diffContent = "@@ -10,3 +12,4 @@\n ctx\n+added\n ctx2")),
        commits = emptyList(),
    )

    private fun at(
        path: String,
        line: Int?,
    ) = Finding(Severity.ERROR, "m", "r", location = Location.FileRef(path, line))

    private val sha1 = "0acba293bcdd7631b269c609458026177bb42a77"

    @Test
    fun `GitLab line links carry the old and new line of the diff and fall back to the file`() {
        val links =
            lineLinks(
                "https://gl/g/p/-/merge_requests/7/",
                changed,
                listOf(at("src/A.kt", 13), at("src/A.kt", 14), at("src/A.kt", 99), at("src/A.kt", null))
            )

        assertEquals(
            mapOf(
                "src/A.kt:13" to "https://gl/g/p/-/merge_requests/7/diffs#${sha1}_11_13",
                "src/A.kt:14" to "https://gl/g/p/-/merge_requests/7/diffs#${sha1}_11_14",
                "src/A.kt:99" to "https://gl/g/p/-/merge_requests/7/diffs#$sha1",
            ),
            links,
        )
    }

    @Test
    fun `GitHub line links name the right side line of the file diff`() {
        assertEquals(
            mapOf(
                "src/A.kt:5" to
                    "https://github.com/o/r/pull/7/files#diff-2c8328c3a60aba38229ccc682e6b3c0617d104f378c5de9004a5e5a8f4006614R5"
            ),
            lineLinks("https://github.com/o/r/pull/7", changed, listOf(at("src/A.kt", 5))),
        )
    }
}
