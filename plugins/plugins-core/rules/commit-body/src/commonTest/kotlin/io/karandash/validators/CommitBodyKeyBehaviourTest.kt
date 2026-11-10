package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitBodyKeyBehaviourTest {
    private fun commit(
        message: String,
        sha: String = "abc1234",
    ) = Commit(sha, message, CommitAuthor("user", "user@example.com"), "2024-01-01T00:00:00Z")

    private fun count(
        config: CommitBodyConfig,
        title: String = "Title",
        vararg messages: String,
    ): Int =
        CommitBodyValidator(config)
            .validate(createTestMergeRequest(title = title, commits = messages.mapIndexed { i, m -> commit(m, "abc123$i") }))
            .size

    @Test
    fun `pattern flags a body that does not match`() {
        val config = CommitBodyConfig(pattern = "CHANGELOG: .+")
        assertEquals(0, count(config, "T", "s\n\nCHANGELOG: done"))
        assertEquals(1, count(config, "T", "s\n\nother text"))
    }

    @Test
    fun `required true flags a commit without a body`() {
        assertEquals(1, count(CommitBodyConfig(required = true), "T", "subject only"))
        assertEquals(0, count(CommitBodyConfig(required = false), "T", "subject only"))
    }

    @Test
    fun `requiredWhenSubjectMatches requires a body only for matching subjects`() {
        val config = CommitBodyConfig(requiredWhenSubjectMatches = "^feat")
        assertEquals(1, count(config, "T", "feat: x", "fix: y"))
    }

    @Test
    fun `requiredWhenTitleMatches requires a body when the MR title matches`() {
        val config = CommitBodyConfig(requiredWhenTitleMatches = "^feat")
        assertEquals(1, count(config, "feat: add", "subject only"))
        assertEquals(0, count(config, "fix: add", "subject only"))
    }

    @Test
    fun `matchMode contains accepts a pattern inside the first body line`() {
        val body = "s\n\nsee CHANGELOG: done today"
        assertEquals(1, count(CommitBodyConfig(pattern = "CHANGELOG: .+", matchMode = "fullMatch"), "T", body))
        assertEquals(0, count(CommitBodyConfig(pattern = "CHANGELOG: .+", matchMode = "contains"), "T", body))
    }

    @Test
    fun `scope anyCommit is satisfied by a single matching commit`() {
        val messages = arrayOf("a\n\nCHANGELOG: one", "b\n\nno marker")
        val pattern = "CHANGELOG: .+"
        assertEquals(1, count(CommitBodyConfig(pattern = pattern, required = true, scope = "perCommit"), "T", *messages))
        assertEquals(0, count(CommitBodyConfig(pattern = pattern, required = true, scope = "anyCommit"), "T", *messages))
    }
}
