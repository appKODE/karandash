package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.patterns.TicketFormat
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossEntityRegressionTest {
    private fun commit(message: String) =
        Commit(
            sha = "abc1234",
            message = message,
            author = CommitAuthor("user", "user@example.com"),
            timestamp = "2024-01-01T00:00:00Z",
        )

    @Test
    fun `extra changelog tasks produce one finding not two`() {
        val mr = createTestMergeRequest(
            title = "[MAPS-1] Work",
            commits = listOf(commit("[MAPS-1] Work\n\nCHANGELOG: MAPS-1 a\nCHANGELOG: MAPS-2 b\nCHANGELOG: MAPS-3 c")),
        )
        val findings = CrossEntityConsistencyValidator(CrossEntityConsistencyConfig()).validate(mr)
        assertEquals(1, findings.size, findings.toString())
        assertTrue(findings.single().message.contains("MAPS-2"))
    }

    @Test
    fun `project key is matched on a word boundary`() {
        val config = CrossEntityConsistencyConfig(
            ticketFormat = TicketFormat.JIRA,
            ticketProjects = listOf("AB"),
        )
        val mr = createTestMergeRequest(
            title = "AB-1 Work",
            commits = listOf(commit("XAB-9 unrelated token")),
        )
        val findings = CrossEntityConsistencyValidator(config).validate(mr)
        assertTrue(findings.isEmpty(), findings.toString())
    }
}
