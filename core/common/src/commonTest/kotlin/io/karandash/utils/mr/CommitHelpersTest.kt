package io.karandash.utils.mr

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommitHelpersTest {
    private fun createMR(commits: List<Commit>) =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = User("testuser", "Test User"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = commits,
            changes = emptyList()
        )

    private fun createCommit(
        message: String,
        authorName: String = "John Doe"
    ) = Commit(
        sha = "abc123",
        message = message,
        author = CommitAuthor(authorName, "john@example.com"),
        timestamp = "2024-01-01T00:00:00Z"
    )

    @Test
    fun testCommitMessages() {
        val mr = createMR(
            listOf(
                createCommit("feat: add feature"),
                createCommit("fix: bug fix"),
                createCommit("docs: update readme")
            )
        )

        val messages = mr.commitMessages()
        assertEquals(3, messages.size)
        assertEquals("feat: add feature", messages[0])
        assertEquals("fix: bug fix", messages[1])
        assertEquals("docs: update readme", messages[2])
    }

    @Test
    fun testCommitMessageEmptyList() {
        val mr = createMR(emptyList())
        assertEquals(emptyList(), mr.commitMessages())
    }

    @Test
    fun testCommitsByAuthor() {
        val mr = createMR(
            listOf(
                createCommit("commit 1", "Alice"),
                createCommit("commit 2", "Bob"),
                createCommit("commit 3", "Alice"),
                createCommit("commit 4", "Charlie")
            )
        )

        val aliceCommits = mr.commitsByAuthor("Alice")
        assertEquals(2, aliceCommits.size)
        assertEquals("commit 1", aliceCommits[0].message)
        assertEquals("commit 3", aliceCommits[1].message)
    }

    @Test
    fun testCommitsByAuthorCaseInsensitive() {
        val mr = createMR(
            listOf(
                createCommit("commit 1", "Alice"),
                createCommit("commit 2", "ALICE"),
                createCommit("commit 3", "alice")
            )
        )

        val aliceCommits = mr.commitsByAuthor("aLiCe")
        assertEquals(3, aliceCommits.size)
    }

    @Test
    fun testCommitsByAuthorNoMatch() {
        val mr = createMR(
            listOf(
                createCommit("commit 1", "Alice"),
                createCommit("commit 2", "Bob")
            )
        )

        val charlieCommits = mr.commitsByAuthor("Charlie")
        assertEquals(emptyList(), charlieCommits)
    }

    @Test
    fun testHasSquashableCommitsDefault() {
        val mr6 = createMR(List(6) { createCommit("commit $it") })
        assertTrue(mr6.hasSquashableCommits())

        val mr5 = createMR(List(5) { createCommit("commit $it") })
        assertFalse(mr5.hasSquashableCommits())

        val mr3 = createMR(List(3) { createCommit("commit $it") })
        assertFalse(mr3.hasSquashableCommits())
    }

    @Test
    fun testHasSquashableCommitsCustomThreshold() {
        val mr = createMR(List(3) { createCommit("commit $it") })
        assertTrue(mr.hasSquashableCommits(maxCommits = 2))
        assertFalse(mr.hasSquashableCommits(maxCommits = 3))
        assertFalse(mr.hasSquashableCommits(maxCommits = 5))
    }

    @Test
    fun testIsFixup() {
        assertTrue(createCommit("fixup! previous commit").isFixup())
        assertTrue(createCommit("squash! previous commit").isFixup())
        assertFalse(createCommit("fix: bug fix").isFixup())
        assertFalse(createCommit("feat: new feature").isFixup())
        assertFalse(createCommit("regular commit").isFixup())
    }

    @Test
    fun testIsConventional() {
        // Valid conventional commits
        assertTrue(createCommit("feat: add new feature").isConventional())
        assertTrue(createCommit("fix: bug fix").isConventional())
        assertTrue(createCommit("docs: update readme").isConventional())
        assertTrue(createCommit("style: format code").isConventional())
        assertTrue(createCommit("refactor: restructure").isConventional())
        assertTrue(createCommit("perf: optimize").isConventional())
        assertTrue(createCommit("test: add tests").isConventional())
        assertTrue(createCommit("build: update deps").isConventional())
        assertTrue(createCommit("ci: fix pipeline").isConventional())
        assertTrue(createCommit("chore: maintenance").isConventional())
        assertTrue(createCommit("revert: undo changes").isConventional())

        // With scope
        assertTrue(createCommit("feat(api): add endpoint").isConventional())
        assertTrue(createCommit("fix(ui): button color").isConventional())

        // Breaking change
        assertTrue(createCommit("feat!: breaking change").isConventional())
        assertTrue(createCommit("feat(scope)!: breaking").isConventional())

        // Invalid conventional commits
        assertFalse(createCommit("feature: add new feature").isConventional())
        assertFalse(createCommit("bugfix: fix issue").isConventional())
        assertFalse(createCommit("WIP: work in progress").isConventional())
        assertFalse(createCommit("random commit message").isConventional())
        assertFalse(createCommit("feat add feature").isConventional()) // missing colon
    }

    @Test
    fun `isConventional uses the commit rule shape and needs a subject`() {
        assertFalse(createCommit("feat:").isConventional())
        assertFalse(createCommit("feat:no space").isConventional())
        assertTrue(createCommit("feat(api)!: subject\n\nbody").isConventional())
        assertEquals(conventionalCommitPattern(), conventionalCommitRegexPattern())
        assertEquals("^(feat|bug\\.fix)(\\(.+\\))?!?: .+", conventionalCommitPattern(listOf("feat", "bug.fix")))
    }
}
