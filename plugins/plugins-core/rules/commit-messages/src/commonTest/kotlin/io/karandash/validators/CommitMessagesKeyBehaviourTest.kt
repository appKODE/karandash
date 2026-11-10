package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitMessagesKeyBehaviourTest {
    private fun messages(
        config: CommitMessagesConfig,
        vararg subjects: String,
    ): List<String> =
        CommitMessagesValidator(config)
            .validate(
                createTestMergeRequest(
                    commits = subjects.mapIndexed { i, s ->
                        Commit("abc123$i", s, CommitAuthor("user", "user@example.com"), "2024-01-01T00:00:00Z")
                    },
                ),
            ).map { it.message }

    @Test
    fun `format conventional flags a free text commit`() {
        assertEquals(emptyList(), messages(CommitMessagesConfig(format = "conventional"), "feat: add login"))
        assertTrue(messages(CommitMessagesConfig(format = "conventional"), "add login").single().contains("conventional"))
    }

    @Test
    fun `pattern flags a commit that does not match`() {
        val config = CommitMessagesConfig(pattern = "[A-Z]+-\\d+ .+")
        assertEquals(emptyList(), messages(config, "ABC-1 do it"))
        assertEquals(1, messages(config, "do it").size)
    }

    @Test
    fun `minLength flags a short first line`() {
        assertTrue(messages(CommitMessagesConfig(minLength = 10), "short").single().contains("chars (minimum"))
        assertEquals(emptyList(), messages(CommitMessagesConfig(minLength = 5), "short"))
    }

    @Test
    fun `maxLength flags a long first line`() {
        assertTrue(messages(CommitMessagesConfig(maxLength = 5), "too long line").single().contains("chars (limit"))
        assertEquals(emptyList(), messages(CommitMessagesConfig(maxLength = 50), "too long line"))
    }

    @Test
    fun `subject maxLength flags a long first line`() {
        val config = CommitMessagesConfig(subject = CommitSubjectConfig(maxLength = 5))
        assertTrue(messages(config, "too long line").single().contains("chars (limit"))
        assertEquals(emptyList(), messages(CommitMessagesConfig(subject = CommitSubjectConfig(maxLength = 50)), "too long line"))
    }

    @Test
    fun `type enables the conventional check`() {
        val config = CommitMessagesConfig(type = CommitTypeConfig())
        assertEquals(1, messages(config, "add login").size)
        assertEquals(emptyList(), messages(config, "fix: add login"))
    }

    @Test
    fun `type allowed rejects a conventional type outside the list`() {
        val config = CommitMessagesConfig(type = CommitTypeConfig(allowed = listOf("feat")))
        assertEquals(emptyList(), messages(config, "feat: add login"))
        assertEquals(1, messages(config, "fix: add login").size)
    }

    @Test
    fun `conventionalTypes rejects a conventional type outside the list`() {
        val config = CommitMessagesConfig(format = "conventional", conventionalTypes = listOf("feat"))
        assertEquals(emptyList(), messages(config, "feat: add login"))
        assertEquals(1, messages(config, "fix: add login").size)
    }
}
