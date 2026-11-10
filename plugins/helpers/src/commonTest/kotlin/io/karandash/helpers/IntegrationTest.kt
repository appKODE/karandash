package io.karandash.helpers

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.mr.addedFiles
import io.karandash.utils.mr.addedLines
import io.karandash.utils.mr.commitMessages
import io.karandash.utils.mr.filesMatching
import io.karandash.utils.mr.hasLabel
import io.karandash.utils.mr.isConventional
import io.karandash.utils.mr.isFixup
import io.karandash.utils.mr.removedLines
import io.karandash.utils.validation.checkRule
import io.karandash.utils.validation.validateFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationTest {
    @Test
    fun `full validation workflow using all helpers`() {
        // Build a realistic MergeRequest
        val mr = MergeRequest(
            id = "123",
            iid = 123,
            title = "feat: Add user authentication",
            description = "Implements JWT-based auth",
            sourceBranch = "feature/auth",
            targetBranch = "main",
            author = User("alice", "Alice", "alice@example.com"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = listOf("enhancement", "security"),
            commits = listOf(
                Commit("abc", "feat: Add JWT validation", CommitAuthor("Alice", "alice@example.com"), "2024-01-01T00:00:00Z"),
                Commit("def", "test: Add auth tests", CommitAuthor("Alice", "alice@example.com"), "2024-01-01T01:00:00Z")
            ),
            changes = listOf(
                FileChange("/src/auth/jwt.kt", ChangeType.ADDED, 50, 0, "+fun validateToken(token: String)"),
                FileChange("/src/auth/middleware.kt", ChangeType.ADDED, 30, 0, "+fun authMiddleware()"),
                FileChange("/tests/auth_test.kt", ChangeType.ADDED, 20, 0, "+@Test fun testAuth()")
            ),
            approvals = emptyList(),
            reviewers = emptyList()
        )

        val findings = mutableListOf<Finding>()

        // Use MrHelpers
        if (!mr.hasLabel("reviewed")) {
            findings += FindingFactory.warn("label-check", "Missing 'reviewed' label")
        }

        // Check total lines changed directly
        if (mr.changes.sumOf { it.additions + it.deletions } > 200) {
            findings += FindingFactory.error("size-check", "MR exceeds 200 lines")
        }

        // Use CommitHelpers
        val nonConventional = mr.commits.filter { !it.isConventional() }
        if (nonConventional.isNotEmpty()) {
            findings += FindingFactory.warn("commit-format", "Non-conventional commits found")
        }

        // Use ValidationHelpers
        findings += mr.validateFiles(Regex("\\.kt$")) { file ->
            if (file.additions > 100) {
                listOf(FindingFactory.warn("file-size", "Large file: ${file.path}"))
            } else {
                emptyList()
            }
        }

        // Verify findings
        assertEquals(1, findings.size, "Should have 1 finding (missing reviewed label)")
        assertEquals("label-check", findings[0].rule)
        assertEquals(Severity.WARNING, findings[0].severity)
    }

    @Test
    fun `checkRule helper simplifies validation logic`() {
        val mr = MergeRequest(
            id = "123",
            iid = 123,
            title = "WIP: Test",
            description = "",
            sourceBranch = "test",
            targetBranch = "main",
            author = User("bob", "Bob", "bob@example.com"),
            state = MergeRequestState.OPENED,
            draft = true,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList()
        )

        // checkRule returns findings only when condition fails
        val findings = checkRule(
            rule = "draft-check",
            severity = Severity.WARNING,
            condition = !mr.draft,
            message = "MR is still in draft"
        )

        assertEquals(1, findings.size)
        assertEquals("draft-check", findings[0].rule)
        assertEquals("MR is still in draft", findings[0].message)
    }

    @Test
    fun `combining all helper categories in realistic scenario`() {
        // Scenario: Large MR with mixed commit quality
        val mr = MergeRequest(
            id = "456",
            iid = 456,
            title = "feat: Refactor authentication system",
            description = "Complete overhaul of auth",
            sourceBranch = "feature/auth-refactor",
            targetBranch = "main",
            author = User("charlie", "Charlie", "charlie@example.com"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = listOf("refactor", "breaking-change"),
            commits = listOf(
                Commit("abc1", "feat: New auth flow", CommitAuthor("Charlie", "charlie@example.com"), "2024-01-01T00:00:00Z"),
                Commit("abc2", "WIP stuff", CommitAuthor("Charlie", "charlie@example.com"), "2024-01-01T01:00:00Z"),
                Commit("abc3", "fix: Handle edge case", CommitAuthor("Charlie", "charlie@example.com"), "2024-01-01T02:00:00Z"),
                Commit("abc4", "more changes", CommitAuthor("Charlie", "charlie@example.com"), "2024-01-01T03:00:00Z")
            ),
            changes = listOf(
                FileChange("/src/auth/core.kt", ChangeType.MODIFIED, 150, 80),
                FileChange("/src/auth/utils.kt", ChangeType.ADDED, 50, 0),
                FileChange("/tests/auth_test.kt", ChangeType.MODIFIED, 30, 10),
                FileChange("/README.md", ChangeType.MODIFIED, 5, 2)
            ),
            approvals = emptyList(),
            reviewers = emptyList()
        )

        val findings = mutableListOf<Finding>()

        // Check: MR size
        if (mr.changes.sumOf { it.additions + it.deletions } > 250) {
            findings +=
                FindingFactory.error("size-limit", "MR has ${mr.changes.sumOf { it.additions + it.deletions }} lines changed (limit: 250)")
        }

        // Check: Non-conventional commits
        val badCommits = mr.commits.filter { !it.isConventional() }
        if (badCommits.isNotEmpty()) {
            findings += FindingFactory.warn("commit-convention", "${badCommits.size} commits don't follow conventional format")
        }

        // Check: Large individual files
        findings += mr.validateFiles(Regex("\\.kt$")) { file ->
            val totalChanges = file.additions + file.deletions
            if (totalChanges > 200) {
                listOf(FindingFactory.warn("file-size", "Large change in ${file.path}: $totalChanges lines"))
            } else {
                emptyList()
            }
        }

        // Check: Label requirements
        findings += checkRule(
            rule = "breaking-change-label",
            severity = Severity.ERROR,
            condition = mr.hasLabel("breaking-change"),
            message = "Breaking changes must be labeled"
        )

        // Verify we detected all issues
        assertTrue(findings.size >= 2, "Should detect multiple issues")
        assertTrue(findings.any { it.rule == "size-limit" }, "Should detect size limit")
        assertTrue(findings.any { it.rule == "commit-convention" }, "Should detect non-conventional commits")
    }

    @Test
    fun `helpers work with minimal MR data`() {
        // Edge case: MR with minimal fields populated
        val mr = MergeRequest(
            id = "789",
            iid = 789,
            title = "fix: Bug fix",
            description = null,
            sourceBranch = "fix/bug",
            targetBranch = "main",
            author = User("dave", "Dave"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList()
        )

        // All helpers should work without errors
        assertEquals(0, mr.changes.sumOf { it.additions + it.deletions })
        assertEquals(0, mr.filesMatching(Regex(".*")).size)
        assertEquals(0, mr.addedFiles().size)
        assertEquals(0, mr.commitMessages().size)

        val findings = mr.validateFiles(Regex(".*")) { emptyList() }
        assertEquals(0, findings.size)
    }

    @Test
    fun `DiffHelpers extract content from changes`() {
        val fileChange = FileChange(
            path = "/src/test.kt",
            changeType = ChangeType.MODIFIED,
            additions = 2,
            deletions = 1,
            diffContent = """
                @@ -1,3 +1,4 @@
                 fun existing() {
                +    println("new line")
                -    // old comment
                +    // new comment
                 }
            """.trimIndent()
        )

        val added = fileChange.addedLines()
        val removed = fileChange.removedLines()

        assertEquals(2, added.size)
        assertTrue(added.any { it.contains("println") })
        assertTrue(added.any { it.contains("new comment") })

        assertEquals(1, removed.size)
        assertTrue(removed.any { it.contains("old comment") })
    }

    @Test
    fun `CommitHelpers detect fixup commits`() {
        val commits = listOf(
            Commit("a1", "feat: New feature", CommitAuthor("User", "user@example.com"), "2024-01-01T00:00:00Z"),
            Commit("a2", "fixup! feat: New feature", CommitAuthor("User", "user@example.com"), "2024-01-01T01:00:00Z"),
            Commit("a3", "squash! feat: New feature", CommitAuthor("User", "user@example.com"), "2024-01-01T02:00:00Z")
        )

        val fixups = commits.filter { it.isFixup() }
        assertEquals(2, fixups.size)
    }
}
