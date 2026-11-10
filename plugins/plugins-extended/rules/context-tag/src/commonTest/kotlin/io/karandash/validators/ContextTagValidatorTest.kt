package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.finding.Severity
import io.karandash.patterns.DelimiterType
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextTagValidatorTest {
    private val defaultConfig = ContextTagConfig(
        allowed = listOf("ios", "android", "core"),
        exactlyOne = true,
    )

    @Test
    fun testValidSingleContextTagPasses() {
        val validator = ContextTagValidator(defaultConfig)
        val mr = createTestMergeRequest(title = "[MAPS-123][ios] Add feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testNoContextTagFails() {
        val validator = ContextTagValidator(defaultConfig)
        val mr = createTestMergeRequest(title = "[MAPS-123] Add feature without tag")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertTrue(findings[0].message.contains("No context tag found"))
    }

    @Test
    fun testMultipleContextTagsFails() {
        val validator = ContextTagValidator(defaultConfig)
        val mr = createTestMergeRequest(title = "[MAPS-123][ios][android] Both platforms")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Multiple context tags"))
    }

    @Test
    fun testMultipleTagsAllowedWhenExactlyOneFalse() {
        val config = defaultConfig.copy(exactlyOne = false)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[MAPS-123][ios][android] Both platforms")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testTaskIdNotCountedAsContextTag() {
        val validator = ContextTagValidator(defaultConfig)
        // MAPS-123 should NOT be treated as a context tag because it contains digits and hyphen
        val mr = createTestMergeRequest(title = "[MAPS-123][core] Fix bug")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCommitTitleValidation() {
        val validator = ContextTagValidator(defaultConfig)
        val commit = createCommit(message = "[MAPS-123] Commit without context tag")
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] MR title is fine",
            commits = listOf(commit),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("commit") && it.message.contains("No context tag") })
    }

    @Test
    fun testDisableCommitTitleCheck() {
        val config = defaultConfig.copy(checkCommitTitles = false)
        val validator = ContextTagValidator(config)
        val commit = createCommit(message = "[MAPS-123] No tag commit")
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] MR title",
            commits = listOf(commit),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testDisableMrTitleCheck() {
        val config = defaultConfig.copy(checkMrTitle = false)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] No tag in MR",
            commits = emptyList(),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testEmptyAllowedListSkipsValidation() {
        val config = ContextTagConfig(allowed = emptyList())
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "Anything goes")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCaseInsensitiveMatching() {
        val validator = ContextTagValidator(defaultConfig)
        val mr = createTestMergeRequest(title = "[MAPS-123][iOS] Capital case tag")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    // --- Custom tag pattern tests ---

    @Test
    fun testCustomTagPatternWithHyphens() {
        val config = ContextTagConfig(
            allowed = listOf("ios-dev", "android-dev"),
            tagPattern = "\\[([a-zA-Z][a-zA-Z0-9_-]*)\\]",
        )
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[MAPS-123][ios-dev] Feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Custom pattern with hyphens should match: $findings")
    }

    @Test
    fun testCaseSensitiveMode() {
        val config = ContextTagConfig(
            allowed = listOf("ios", "android"),
            caseSensitive = true,
        )
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[MAPS-123][iOS] Capital case tag")

        val findings = validator.validate(mr)
        assertEquals(listOf("MR title: tag [iOS] is not accepted — tags are case-sensitive here."), findings.map { it.message })
        assertEquals("Use [ios] exactly, or set caseSensitive: false.", findings.single().suggestion)
    }

    @Test
    fun `wrong case tag in a commit names the commit`() {
        val config = ContextTagConfig(allowed = listOf("ios"), caseSensitive = true, checkMrTitle = false)
        val mr = createTestMergeRequest(commits = listOf(createCommit(sha = "a1b2c3d4e5", message = "[iOS] fix")))

        assertEquals(
            listOf("Commit a1b2c3d: tag [iOS] is not accepted — tags are case-sensitive here."),
            ContextTagValidator(config).validate(mr).map { it.message },
        )
    }

    @Test
    fun `commits without a tag fold into one finding`() {
        val config = ContextTagConfig(allowed = listOf("ios"), checkMrTitle = false)
        val commits = (1..4).map { createCommit(sha = "bbbb00$it", message = "fix $it") }

        val findings = ContextTagValidator(config).validate(createTestMergeRequest(commits = commits))

        assertEquals(listOf("4 commits have no context tag (bbbb001, bbbb002, bbbb003, +1 more)"), findings.map { it.message })
    }

    @Test
    fun testCaseSensitiveModeExactMatch() {
        val config = ContextTagConfig(
            allowed = listOf("iOS", "Android"),
            caseSensitive = true,
        )
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[MAPS-123][iOS] Feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Case-sensitive with exact match should pass: $findings")
    }

    @Test
    fun testInvalidTagPatternProducesError() {
        val config = ContextTagConfig(
            allowed = listOf("ios"),
            tagPattern = "[invalid",
        )
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[MAPS-123][ios] Feature")

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("Invalid regex pattern") })
    }

    @Test
    fun testCurlyBraceTagPattern() {
        val config = ContextTagConfig(
            allowed = listOf("ios", "android"),
            tagPattern = "\\{([a-zA-Z][a-zA-Z0-9_]*)\\}",
        )
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[MAPS-123]{ios} Feature")

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Curly brace pattern should match: $findings")
    }

    @Test
    fun `suggestions show tags in the configured delimiter`() {
        val config = ContextTagConfig(allowed = listOf("ios", "android"), delimiter = DelimiterType.BRACES, checkCommitTitles = false)
        val validator = ContextTagValidator(config)

        assertEquals(
            listOf("Add one of the allowed context tags to the MR title: `{ios}`, `{android}`."),
            validator.validate(createTestMergeRequest(title = "[ios] fix")).map { it.suggestion },
        )
        assertEquals(
            listOf("Multiple context tags found in MR title: {ios}, {android}."),
            validator.validate(createTestMergeRequest(title = "{ios}{android} fix")).map { it.message },
        )
        assertEquals(
            listOf("Add one of the allowed context tags to the MR title: `ios`, `android`."),
            ContextTagValidator(
                config.copy(delimiter = DelimiterType.NONE)
            ).validate(createTestMergeRequest(title = "fix")).map { it.suggestion },
        )
    }

    @Test
    fun `default delimiter suggestions keep square brackets`() {
        val validator = ContextTagValidator(ContextTagConfig(allowed = listOf("ios", "core"), checkCommitTitles = false))

        assertEquals(
            listOf("Add one of the allowed context tags to the MR title: `[ios]`, `[core]`."),
            validator.validate(createTestMergeRequest(title = "(ios) fix")).map { it.suggestion },
        )
    }

    private fun createCommit(
        sha: String = "abc1234",
        message: String = "test commit",
    ): Commit =
        Commit(
            sha = sha,
            message = message,
            author = CommitAuthor("user", "user@example.com"),
            timestamp = "2024-01-01T00:00:00Z",
        )
}
