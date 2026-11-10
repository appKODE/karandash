package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguagePolicyKeyBehaviourTest {
    private val off =
        LanguagePolicyConfig(
            checkMrTitle = false,
            checkMrDescription = false,
            checkCommitTitle = false,
            checkCommitDescription = false,
            checkBranchName = false,
        )
    private val cyrillic = "привет"

    private fun messages(
        config: LanguagePolicyConfig,
        title: String = "ok",
        description: String? = null,
        branch: String = "feature/x",
        commit: String? = null,
    ): List<String> {
        val commits = commit?.let { listOf(Commit("abc1234", it, CommitAuthor("u", "e"), "2024-01-01T00:00:00Z")) }.orEmpty()
        return LanguagePolicyValidator(config)
            .validate(createTestMergeRequest(title = title, description = description, sourceBranch = branch, commits = commits))
            .map { it.message }
    }

    @Test
    fun `checkMrTitle flags a non allowed title`() {
        assertEquals(1, messages(off.copy(checkMrTitle = true), title = cyrillic).size)
        assertEquals(0, messages(off, title = cyrillic).size)
    }

    @Test
    fun `checkMrDescription flags a non allowed description`() {
        assertEquals(1, messages(off.copy(checkMrDescription = true), description = cyrillic).size)
        assertEquals(0, messages(off, description = cyrillic).size)
    }

    @Test
    fun `checkCommitTitle flags a non allowed commit title`() {
        assertEquals(1, messages(off.copy(checkCommitTitle = true), commit = cyrillic).size)
        assertEquals(0, messages(off, commit = cyrillic).size)
    }

    @Test
    fun `checkCommitDescription flags a non allowed commit body`() {
        assertEquals(1, messages(off.copy(checkCommitDescription = true), commit = "ok\n\n$cyrillic").size)
        assertEquals(0, messages(off, commit = "ok\n\n$cyrillic").size)
    }

    @Test
    fun `checkBranchName flags a non allowed branch name`() {
        assertEquals(1, messages(off.copy(checkBranchName = true), branch = "feature/$cyrillic").size)
        assertEquals(0, messages(off, branch = "feature/$cyrillic").size)
    }

    @Test
    fun `allowedScripts decides which letters pass`() {
        val latin = off.copy(checkMrTitle = true)
        assertEquals(1, messages(latin, title = cyrillic).size)
        assertEquals(0, messages(latin.copy(allowedScripts = listOf("latin", "cyrillic")), title = cyrillic).size)
    }

    @Test
    fun `additionalAllowedRanges adds code points to the allowed set`() {
        val without = off.copy(checkMrTitle = true)
        assertEquals(1, messages(without, title = cyrillic).size)
        val config = without.copy(additionalAllowedRanges = listOf("0x0400-0x04FF"))
        assertEquals(0, messages(config, title = cyrillic).size)
    }

    @Test
    fun `languageName is named in the fix hint`() {
        val config = off.copy(checkMrTitle = true, languageName = "Klingon")
        val finding = LanguagePolicyValidator(config).validate(createTestMergeRequest(title = cyrillic)).single()
        assertTrue(finding.suggestion.orEmpty().contains("Klingon"))
    }

    @Test
    fun `unknownScripts warns about an unrecognised allowed script`() {
        val config = off.copy(allowedScripts = listOf("latin", "klingon"))
        assertTrue(messages(config).single().contains("klingon"))
        assertEquals(0, messages(off).size)
    }
}
