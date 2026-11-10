package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Messages do not hardcode CHANGELOG. */
class CommitBodyRegressionTest {
    @Test
    fun `messages name the configured pattern not a fixed CHANGELOG prefix`() {
        val config = CommitBodyConfig(pattern = "^Release-Note: .+", required = true)
        val commit = Commit(
            sha = "abc1234",
            message = "fix: a",
            author = CommitAuthor("user", "user@example.com"),
            timestamp = "2024-01-01T00:00:00Z",
        )
        val finding = CommitBodyValidator(config).validate(createTestMergeRequest(commits = listOf(commit))).single()
        assertFalse(finding.suggestion.orEmpty().contains("CHANGELOG"), finding.suggestion.orEmpty())
        assertTrue(finding.suggestion.orEmpty().contains("Release-Note"), finding.suggestion.orEmpty())
    }
}
