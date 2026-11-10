package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.finding.mismatch
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommitMessagesFixHintTest {
    private fun mrWith(vararg messages: String) =
        createTestMergeRequest(
            commits = messages.mapIndexed { i, message ->
                Commit(sha = "abcdef$i", message = message, author = CommitAuthor("u", "u@example.com"), timestamp = "2024-01-01T00:00:00Z")
            },
        )

    @Test
    fun `pattern mismatch puts the fix in the suggestion and the format in metadata`() {
        val finding = CommitMessagesValidator(CommitMessagesConfig(pattern = "^\\[MAPS-\\d+\\] .+$"))
            .validate(mrWith("wip one"))
            .single()

        assertEquals("Commit message does not match required pattern: abcdef0", finding.message)
        assertTrue(finding.suggestion.orEmpty().startsWith("Change the commit message to"), finding.suggestion)
        assertNotNull(finding.metadata?.get(WellKnownMetadataKeys.EXPECTED_FORMAT))
        assertEquals(0, finding.mismatch()?.position)
        assertTrue(finding.fixSuggestions.isEmpty())
    }

    @Test
    fun `conventional mismatch with a missing colon gets the exact correction`() {
        val finding = CommitMessagesValidator(CommitMessagesConfig(format = "conventional"))
            .validate(mrWith("feat add login"))
            .single()

        assertEquals("Change the commit message to `feat: add login`.", finding.suggestion)
        assertEquals(4, finding.mismatch()?.position)
        assertNotNull(finding.metadata?.get(WellKnownMetadataKeys.EXPECTED_FORMAT))
        assertTrue(finding.fixSuggestions.isEmpty())
    }

    @Test
    fun `conventional mismatch without a type infers one from the message`() {
        val finding = CommitMessagesValidator(CommitMessagesConfig(format = "conventional"))
            .validate(mrWith("fix the login bug"))
            .single()

        assertEquals("Change the commit message to `fix: the login bug`.", finding.suggestion)
    }

    @Test
    fun `conventional mismatch with one possible separator gets the exact correction`() {
        val finding = CommitMessagesValidator(CommitMessagesConfig(format = "conventional"))
            .validate(mrWith("feat!add login"))
            .single()

        assertEquals("Change the commit message to `feat!: add login`.", finding.suggestion)
    }
}
