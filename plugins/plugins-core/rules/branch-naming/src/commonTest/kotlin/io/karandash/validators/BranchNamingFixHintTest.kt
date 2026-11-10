package io.karandash.validators

import io.karandash.models.finding.mismatch
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BranchNamingFixHintTest {
    @Test
    fun `pattern mismatch states the problem and fixes towards the closest pattern`() {
        val config = BranchNamingConfig(allowedPatterns = listOf("^feature/.+$", "^(bugfix|hotfix)/.+$"))
        val finding = BranchNamingValidator(config)
            .validate(createTestMergeRequest(sourceBranch = "bugfixlogin"))
            .single()

        assertEquals("Source branch 'bugfixlogin' does not match any allowed pattern.", finding.message)
        assertEquals("Change the branch name to `bugfix/login`.", finding.suggestion)
        assertEquals(6, finding.mismatch()?.position)
        assertNotNull(finding.metadata?.get(WellKnownMetadataKeys.EXPECTED_FORMAT))
        assertTrue(finding.fixSuggestions.isEmpty(), "branches are never autofixed")
    }

    @Test
    fun `structured prefix rule states the problem and lists the prefixes in the suggestion`() {
        val finding = BranchNamingValidator(BranchNamingConfig(prefixOneOf = listOf("feat/", "fix/")))
            .validate(createTestMergeRequest(sourceBranch = "feature/x"))
            .single()

        assertFalse(finding.message.contains("must start"), finding.message)
        assertEquals("Source branch 'feature/x' has none of the allowed prefixes.", finding.message)
        assertEquals("Rename the branch to start with one of `feat/`, `fix/`.", finding.suggestion)
    }
}
