package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Commits failing the same format check produce one grouped finding, not one row per commit. */
class CommitMessagesGroupingTest {
    private fun commit(
        sha: String,
        message: String,
    ) = Commit(sha = sha, message = message, author = CommitAuthor("user", "user@example.com"), timestamp = "2024-01-01T00:00:00Z")

    private val mr = createTestMergeRequest(
        commits = listOf(
            commit("aaaaaaa1", "wip one"),
            commit("bbbbbbb2", "[MAPS-1] good"),
            commit("ccccccc3", "wip three"),
        ),
    )

    @Test
    fun `pattern mismatches across commits produce one finding listing every failing sha`() {
        val findings = CommitMessagesValidator(CommitMessagesConfig(pattern = "^\\[MAPS-\\d+\\].*")).validate(mr)

        assertEquals(1, findings.size, findings.joinToString("\n") { it.message })
        assertEquals(
            "Commit message does not match required pattern: aaaaaaa, ccccccc",
            findings.single().message,
        )
    }

    @Test
    fun `conventional format mismatches across commits produce one finding`() {
        val findings = CommitMessagesValidator(CommitMessagesConfig(format = "conventional")).validate(
            createTestMergeRequest(commits = listOf(commit("aaaaaaa1", "wip one"), commit("ccccccc3", "wip three"))),
        )

        assertEquals(1, findings.size, findings.joinToString("\n") { it.message })
        val message = findings.single().message
        assertTrue("aaaaaaa" in message && "ccccccc" in message, message)
    }

    @Test
    fun `pattern fingerprint is identical for two and three failing commits`() {
        fun fingerprint(failing: Int): String? {
            val commits = (1..failing).map { commit("sha${it}0000", "wip $it") } + commit("good0001", "[MAPS-1] ok")
            return CommitMessagesValidator(CommitMessagesConfig(pattern = "^\\[MAPS-\\d+\\].*"))
                .validate(createTestMergeRequest(commits = commits))
                .single()
                .fingerprint
        }

        assertEquals(fingerprint(2), fingerprint(3))
        assertEquals(fingerprint(1), fingerprint(3))
    }

    @Test
    fun `conventional fingerprint is identical for two and three failing commits`() {
        fun fingerprint(failing: Int): String? {
            val commits = (1..failing).map { commit("sha${it}0000", "wip $it") } + commit("good0001", "feat: ok")
            return CommitMessagesValidator(CommitMessagesConfig(format = "conventional"))
                .validate(createTestMergeRequest(commits = commits))
                .single()
                .fingerprint
        }

        assertEquals(fingerprint(2), fingerprint(3))
        assertEquals(fingerprint(1), fingerprint(3))
    }

    @Test
    fun `message names every failing sha`() {
        val message = CommitMessagesValidator(CommitMessagesConfig(format = "conventional")).validate(mr).single().message

        assertEquals("Commit message does not follow conventional format: aaaaaaa, bbbbbbb, ccccccc", message)
    }

    @Test
    fun `more than three failing commits attach the full list`() {
        val commits = (1..5).map { commit("sha${it}0000", "wip $it") }
        val finding = CommitMessagesValidator(CommitMessagesConfig(pattern = "^\\[MAPS-\\d+\\].*"))
            .validate(createTestMergeRequest(commits = commits))
            .single()

        val attachment = finding.attachments.single()
        assertEquals("All 5 commits", attachment.filename)
        assertTrue("- sha5000 wip 5" in attachment.content, attachment.content)
        assertTrue("+2 more" in finding.message, finding.message)
    }

    @Test
    fun `failing commits stay out of the explanation a config may replace`() {
        val finding = CommitMessagesValidator(CommitMessagesConfig(pattern = "^\\[MAPS-\\d+\\].*")).validate(mr).single()

        assertEquals(CommitMessagesValidator.DEFAULT_EXPLANATION, finding.explanation)
    }

    @Test
    fun `invalid pattern and unknown format are reported once not per commit`() {
        val many = createTestMergeRequest(commits = listOf(commit("aaaaaaa1", "x"), commit("ccccccc3", "y")))

        assertEquals(1, CommitMessagesValidator(CommitMessagesConfig(pattern = "[invalid")).validate(many).size)
        assertEquals(1, CommitMessagesValidator(CommitMessagesConfig(format = "bogus")).validate(many).size)
    }
}
