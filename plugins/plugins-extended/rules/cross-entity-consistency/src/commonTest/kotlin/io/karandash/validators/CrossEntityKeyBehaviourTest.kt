package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.patterns.TicketFormat
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class CrossEntityKeyBehaviourTest {
    private val off =
        CrossEntityConsistencyConfig(
            commitTasksSubsetOfMr = false,
            changelogConsistency = false,
            changelogDuplicateDetection = false,
        )

    private fun count(
        config: CrossEntityConsistencyConfig,
        title: String,
        vararg commitMessages: String,
        description: String? = null,
    ): Int {
        val commits = commitMessages.mapIndexed { i, m -> Commit("abc123$i", m, CommitAuthor("u", "e"), "2024-01-01T00:00:00Z") }
        val mr = createTestMergeRequest(title = title, description = description, commits = commits)
        return CrossEntityConsistencyValidator(config).validate(mr).size
    }

    @Test
    fun `commitTasksSubsetOfMr flags a commit task missing from the MR`() {
        assertEquals(1, count(off.copy(commitTasksSubsetOfMr = true), "ABC-1 x", "ABC-2 y"))
        assertEquals(0, count(off, "ABC-1 x", "ABC-2 y"))
    }

    @Test
    fun `changelogConsistency flags a changelog task missing from the MR`() {
        val commit = "feat\n\nCHANGELOG: ABC-2"
        assertEquals(1, count(off.copy(changelogConsistency = true), "ABC-1 x", commit))
        assertEquals(0, count(off, "ABC-1 x", commit))
    }

    @Test
    fun `changelogDuplicateDetection flags a task listed twice`() {
        val commit = "feat\n\nCHANGELOG: ABC-1"
        assertEquals(1, count(off.copy(changelogDuplicateDetection = true), "ABC-1 x", commit, commit))
        assertEquals(0, count(off, "ABC-1 x", commit, commit))
    }

    @Test
    fun `changelogPrefix selects the changelog lines`() {
        val config = off.copy(changelogConsistency = true, changelogPrefix = "NOTES:")
        assertEquals(1, count(config, "ABC-1 x", "feat\n\nNOTES: ABC-2"))
        assertEquals(0, count(config, "ABC-1 x", "feat\n\nCHANGELOG: ABC-2"))
    }

    @Test
    fun `mrTaskSource decides where MR tasks are read from`() {
        val base = off.copy(commitTasksSubsetOfMr = true)
        assertEquals(1, count(base.copy(mrTaskSource = "title"), "ABC-1 x", "ABC-2 y", description = "ABC-2"))
        assertEquals(0, count(base.copy(mrTaskSource = "description"), "ABC-1 x", "ABC-2 y", description = "ABC-2"))
    }

    @Test
    fun `changelogDuplicateRequiresMatchingText flags only identical entries`() {
        val first = "feat\n\nCHANGELOG: ABC-1 first"
        val second = "fix\n\nCHANGELOG: ABC-1 second"
        val base = off.copy(changelogDuplicateDetection = true)
        assertEquals(1, count(base.copy(changelogDuplicateRequiresMatchingText = false), "ABC-1 x", first, second))
        assertEquals(0, count(base.copy(changelogDuplicateRequiresMatchingText = true), "ABC-1 x", first, second))
    }

    @Test
    fun `changelogCoverage flags an MR task without a changelog entry`() {
        val base = off.copy(changelogConsistency = true)
        val commit = "feat\n\nCHANGELOG: ABC-1"
        assertEquals(1, count(base.copy(changelogCoverage = true), "ABC-1 ABC-2", commit))
        assertEquals(0, count(base.copy(changelogCoverage = false), "ABC-1 ABC-2", commit))
    }

    @Test
    fun `taskIdPattern replaces the default task id pattern`() {
        val config = off.copy(commitTasksSubsetOfMr = true, taskIdPattern = "TASK-\\d+")
        assertEquals(1, count(config, "TASK-1 x", "TASK-2 y"))
        assertEquals(0, count(config, "TASK-1 x", "ABC-2 y"))
    }

    @Test
    fun `ticketFormat selects the structured task id pattern`() {
        val base = off.copy(commitTasksSubsetOfMr = true)
        assertEquals(1, count(base.copy(ticketFormat = TicketFormat.GITHUB), "fix #1", "fix #2"))
        assertEquals(0, count(base, "fix #1", "fix #2"))
    }

    @Test
    fun `ticketProjects limits the structured pattern to listed projects`() {
        val base = off.copy(commitTasksSubsetOfMr = true, ticketFormat = TicketFormat.JIRA)
        assertEquals(0, count(base.copy(ticketProjects = listOf("ABC")), "ABC-1 x", "XYZ-2 y"))
        assertEquals(1, count(base.copy(ticketProjects = listOf("XYZ")), "ABC-1 x", "XYZ-2 y"))
    }
}
