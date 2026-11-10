package io.karandash.plugins.ai.core.config

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConditionalSectionCheckerTest {
    private fun makeMr(
        description: String?,
        changedPaths: List<String> = emptyList(),
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test MR",
        description = description,
        sourceBranch = "feature/test",
        targetBranch = "main",
        author = User(username = "dev", name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList<Commit>(),
        changes = changedPaths.map { FileChange(path = it, changeType = ChangeType.MODIFIED) },
    )

    @Test
    fun `no sections configured returns empty`() {
        val mr = makeMr(description = "Some description", changedPaths = listOf("schema.sql"))
        val result = ConditionalSectionChecker.check(mr, emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `section not triggered when no files match pattern`() {
        val section = ConditionalSection(
            section = "## Database Changes",
            whenFilesChanged = listOf("**/*.sql"),
        )
        val mr = makeMr(description = "Some description", changedPaths = listOf("src/main.kt"))
        val result = ConditionalSectionChecker.check(mr, listOf(section))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `missing section produces finding when files match`() {
        val section = ConditionalSection(
            section = "## Database Changes",
            whenFilesChanged = listOf("**/*.sql"),
        )
        val mr = makeMr(description = "Some description", changedPaths = listOf("db/migrate.sql"))
        val result = ConditionalSectionChecker.check(mr, listOf(section))
        assertEquals(1, result.size)
        assertEquals("description-format:conditional-section", result[0].rule)
        assertTrue(result[0].message.contains("## Database Changes"))
    }

    @Test
    fun `present section produces no finding`() {
        val section = ConditionalSection(
            section = "## Database Changes",
            whenFilesChanged = listOf("**/*.sql"),
        )
        val mr = makeMr(
            description = "## Database Changes\nAdded migration.",
            changedPaths = listOf("db/migrate.sql"),
        )
        val result = ConditionalSectionChecker.check(mr, listOf(section))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `severity from config is applied`() {
        val section = ConditionalSection(
            section = "## Security Impact",
            whenFilesChanged = listOf("**/*.kt"),
            severity = "error",
        )
        val mr = makeMr(description = "No security section", changedPaths = listOf("src/Auth.kt"))
        val result = ConditionalSectionChecker.check(mr, listOf(section))
        assertEquals(1, result.size)
        assertEquals(Severity.ERROR, result[0].severity)
    }

    @Test
    fun `default severity is WARNING`() {
        val section = ConditionalSection(
            section = "## Test Plan",
            whenFilesChanged = listOf("**/*.kt"),
        )
        val mr = makeMr(description = "No test plan", changedPaths = listOf("src/Foo.kt"))
        val result = ConditionalSectionChecker.check(mr, listOf(section))
        assertEquals(Severity.WARNING, result[0].severity)
    }

    @Test
    fun `multiple sections checked independently`() {
        val sections = listOf(
            ConditionalSection(section = "## DB Changes", whenFilesChanged = listOf("**/*.sql")),
            ConditionalSection(section = "## API Changes", whenFilesChanged = listOf("**/api/**")),
        )
        val mr = makeMr(
            description = "## DB Changes\nSome migration.",
            changedPaths = listOf("db/migrate.sql", "src/api/Endpoint.kt"),
        )
        val result = ConditionalSectionChecker.check(mr, sections)
        assertEquals(1, result.size)
        assertTrue(result[0].message.contains("## API Changes"))
    }

    @Test
    fun `null description treated as empty string`() {
        val section = ConditionalSection(
            section = "## Summary",
            whenFilesChanged = listOf("**/*.kt"),
        )
        val mr = makeMr(description = null, changedPaths = listOf("src/Foo.kt"))
        val result = ConditionalSectionChecker.check(mr, listOf(section))
        assertEquals(1, result.size)
    }

    @Test
    fun `no changed files means no section is triggered`() {
        val section = ConditionalSection(
            section = "## Summary",
            whenFilesChanged = listOf("**/*.kt"),
        )
        val mr = makeMr(description = "No summary", changedPaths = emptyList())
        val result = ConditionalSectionChecker.check(mr, listOf(section))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `optional section yields INFO and description is appended`() {
        val section = ConditionalSection(
            section = "## Performance",
            whenFilesChanged = listOf("**/db/**"),
            severity = "error",
            required = false,
            description = "Describe the latency impact",
        )
        val finding = ConditionalSectionChecker.check(makeMr("", listOf("src/db/Repo.kt")), listOf(section)).single()

        assertEquals(Severity.INFO, finding.severity)
        assertTrue(finding.message.startsWith("Missing recommended section '## Performance'"))
        assertTrue(finding.message.endsWith(": Describe the latency impact"))
    }

    @Test
    fun `whenFilesChanged is a GlobMatcher path glob anchored at the repo root`() {
        val section = ConditionalSection(section = "## Migrations", whenFilesChanged = listOf("**/migrations/**"))
        val atRoot = makeMr(description = "", changedPaths = listOf("migrations/001.sql"))
        val outside = makeMr(description = "", changedPaths = listOf("docs/notmigrations/a.md"))

        assertEquals(1, ConditionalSectionChecker.check(atRoot, listOf(section)).size)
        assertTrue(ConditionalSectionChecker.check(outside, listOf(section)).isEmpty())
    }

    @Test
    fun `a heading in another case satisfies the section`() {
        val section = ConditionalSection(section = "## Database Changes", whenFilesChanged = listOf("**/*.sql"))
        val mr = makeMr(description = "Intro\n## database changes \nAdded a column", changedPaths = listOf("db/migrate.sql"))
        assertTrue(ConditionalSectionChecker.check(mr, listOf(section)).isEmpty())
    }

    @Test
    fun `the heading words mid-sentence do not count as the section`() {
        val section = ConditionalSection(section = "## Database Changes", whenFilesChanged = listOf("**/*.sql"))
        val mr = makeMr(description = "No ## Database Changes section needed here", changedPaths = listOf("db/migrate.sql"))
        assertEquals(1, ConditionalSectionChecker.check(mr, listOf(section)).size)
    }
}
