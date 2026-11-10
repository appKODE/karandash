package io.karandash.validators

import io.karandash.patterns.CaseType
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchNamingKeyBehaviourTest {
    private fun messages(
        config: BranchNamingConfig,
        source: String = "feature/ok",
        target: String = "main",
    ): List<String> =
        BranchNamingValidator(config).validate(createTestMergeRequest(sourceBranch = source, targetBranch = target)).map { it.message }

    @Test
    fun `pattern flags a source branch that does not match`() {
        assertTrue(messages(BranchNamingConfig(pattern = "feature/.*"), source = "fix/x").single().startsWith("Source branch"))
        assertEquals(emptyList(), messages(BranchNamingConfig(pattern = "feature/.*"), source = "feature/x"))
    }

    @Test
    fun `allowedPatterns accepts any listed pattern and rejects others`() {
        val config = BranchNamingConfig(allowedPatterns = listOf("feature/.*", "fix/.*"))
        assertEquals(emptyList(), messages(config, source = "fix/x"))
        assertEquals(1, messages(config, source = "chore/x").size)
    }

    @Test
    fun `requireSourceMatch false skips the source branch check`() {
        val config = BranchNamingConfig(pattern = "feature/.*", requireSourceMatch = false)
        assertEquals(emptyList(), messages(config, source = "fix/x"))
    }

    @Test
    fun `requireTargetMatch true flags a target branch that does not match`() {
        val config = BranchNamingConfig(pattern = "feature/.*|main", requireTargetMatch = true)
        assertEquals(emptyList(), messages(config, target = "main"))
        assertTrue(messages(config, target = "develop").single().startsWith("Target branch"))
    }

    @Test
    fun `prefixOneOf flags a branch without an allowed prefix`() {
        val config = BranchNamingConfig(prefixOneOf = listOf("feature/", "fix/"))
        assertEquals(emptyList(), messages(config, source = "fix/x"))
        assertTrue(messages(config, source = "chore/x").single().contains("none of the allowed prefixes"))
    }

    @Test
    fun `case kebab flags an upper case branch body`() {
        val config = BranchNamingConfig(prefixOneOf = listOf("feature/"), case = CaseType.KEBAB)
        assertEquals(emptyList(), messages(config, source = "feature/add-login"))
        assertTrue(messages(config, source = "feature/AddLogin").single().contains("body is not"))
    }

    @Test
    fun `maxLength flags a branch name that is too long`() {
        val config = BranchNamingConfig(maxLength = 10)
        assertEquals(emptyList(), messages(config, source = "feature/x"))
        assertTrue(messages(config, source = "feature/very-long-name").single().contains("exceeds max length 10"))
    }
}
