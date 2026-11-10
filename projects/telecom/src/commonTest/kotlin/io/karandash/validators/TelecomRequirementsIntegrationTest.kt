package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests validating TEL rule behavior in isolation.
 * Split into focused test classes by validation domain to satisfy detekt LargeClass threshold.
 */

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

private const val TEL_TITLE_PATTERN = "^(\\[[A-Z]+-\\d+\\] )?.+"

// =========================================================================
// TITLE FORMAT (MR + Commit)
// Format: [TEL-123] Title text (space after ticket, no platform tag)
// =========================================================================

class TelecomTitleFormatTest {
    @Test
    fun `title with task id and space passes`() {
        val config = TitleFormatConfig(pattern = TEL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[TEL-42] Add device profile screen")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `title without task id passes`() {
        val config = TitleFormatConfig(pattern = TEL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "Fix null pointer in login flow")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `title with multiple task ids passes`() {
        val config = TitleFormatConfig(pattern = TEL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[TEL-100] [TEL-101] Batch fix for Telecom activation")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `title too short fails`() {
        val config = TitleFormatConfig(pattern = TEL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "Hi")
        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty())
    }

    @Test
    fun `title exceeding max length fails`() {
        val config = TitleFormatConfig(pattern = TEL_TITLE_PATTERN, minLength = 10, maxLength = 120)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[TEL-42] " + "A".repeat(120))
        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty())
    }

    @Test
    fun `title without space after ticket bracket still matches base pattern`() {
        val config = TitleFormatConfig(pattern = TEL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[TEL-42]Add device profile screen")
        assertTrue(validator.validate(mr).isEmpty())
    }
}

// =========================================================================
// COMMIT MESSAGE FORMAT
// =========================================================================

class TelecomCommitMessagesTest {
    @Test
    fun `commit messages with task id pass`() {
        val config = CommitMessagesConfig(pattern = TEL_TITLE_PATTERN)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[TEL-42] Implement device profile UI"),
                createCommit("bbb2222", "[TEL-42] Add unit tests"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `commit messages without task id pass`() {
        val config = CommitMessagesConfig(pattern = TEL_TITLE_PATTERN)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "Fix null pointer in login"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `commit message exceeding max length fails`() {
        val config = CommitMessagesConfig(pattern = TEL_TITLE_PATTERN, maxLength = 72)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[TEL-42] " + "A".repeat(72)),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty())
    }
}

// =========================================================================
// CROSS-ENTITY CONSISTENCY
// =========================================================================

class TelecomCrossEntityConsistencyTest {
    @Test
    fun `commit subset of mr passes`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[TEL-42] [TEL-43] Multi-task MR",
            commits = listOf(
                createCommit("aaa1111", "[TEL-42] Part 1"),
                createCommit("bbb2222", "[TEL-43] Part 2"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `commit has extra task fails`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[TEL-42] Single task MR",
            commits = listOf(
                createCommit("aaa1111", "[TEL-42] [TEL-999] Commit references extra task"),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("TEL-999") })
    }

    @Test
    fun `changelog count within mr passes`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[TEL-42] [TEL-43] Feature",
            commits = listOf(
                createCommit("aaa1111", "[TEL-42] Work\n\nCHANGELOG: [TEL-42] Task one."),
                createCommit("bbb2222", "[TEL-43] Work\n\nCHANGELOG: [TEL-43] Task two."),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `changelog has extra tasks fails`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[TEL-42] Single task",
            commits = listOf(
                createCommit(
                    "aaa1111",
                    "[TEL-42] Work\n\nCHANGELOG: [TEL-42] Task one, [TEL-99] Sneaky extra task.",
                ),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("CHANGELOG") })
    }
}

// =========================================================================
// LANGUAGE POLICY
// =========================================================================

class TelecomLanguagePolicyTest {
    @Test
    fun `all english passes`() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[TEL-42] Add device profile screen",
            description = "Added new device profile screen.",
            sourceBranch = "feature/TEL-42-device-profile",
            commits = listOf(
                createCommit("aaa1111", "[TEL-42] Implement device profile"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `cyrillic in title fails`() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[TEL-42] \u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u043f\u0440\u043e\u0444\u0438\u043b\u044c",
            sourceBranch = "feature/test",
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("is not in") && it.message.contains("MR title") })
    }

    @Test
    fun `cyrillic in commit title fails`() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[TEL-42] Fix bug",
            sourceBranch = "feature/test",
            commits = listOf(
                createCommit(
                    "aaa1111",
                    "[TEL-42] \u0418\u0441\u043f\u0440\u0430\u0432\u0438\u0442\u044c \u043e\u0448\u0438\u0431\u043a\u0443"
                ),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("is not in") && it.message.startsWith("Commit") })
    }

    @Test
    fun `accented latin allowed passes`() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[TEL-42] Update cafe resume feature",
            sourceBranch = "feature/test",
        )
        assertTrue(validator.validate(mr).isEmpty())
    }
}

// =========================================================================
// JIRA TICKET VALIDATION
// =========================================================================

class TelecomJiraTicketTest {
    @Test
    fun `correct project key passes`() {
        val config = JiraTicketConfig(projectKeys = listOf("TEL"))
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "[TEL-42] Feature")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `wrong project key fails`() {
        val config = JiraTicketConfig(projectKeys = listOf("TEL"))
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-123] Wrong project")
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("Invalid JIRA project key") })
    }
}

// =========================================================================
// FULL MR EXAMPLES
// =========================================================================

class TelecomFullMrValidationTest {
    @Test
    fun `full valid mr all rules pass`() {
        val titleValidator = TitleFormatValidator(TitleFormatConfig(pattern = TEL_TITLE_PATTERN, minLength = 10))
        val commitValidator = CommitMessagesValidator(CommitMessagesConfig(pattern = TEL_TITLE_PATTERN))
        val crossValidator = CrossEntityConsistencyValidator(CrossEntityConsistencyConfig())
        val langValidator = LanguagePolicyValidator(LanguagePolicyConfig())
        val jiraValidator = JiraTicketValidator(JiraTicketConfig(projectKeys = listOf("TEL")))

        val mr = createTestMergeRequest(
            title = "[TEL-42] Add device profile screen with activation",
            description = "Added new device profile screen with Telecom activation.",
            sourceBranch = "feature/TEL-42-device-profile",
            commits = listOf(
                createCommit("aaa1111", "[TEL-42] Implement device profile UI\n\nCHANGELOG: [TEL-42] Device profile screen."),
            ),
        )

        assertTrue(titleValidator.validate(mr).isEmpty(), "Title format failed")
        assertTrue(commitValidator.validate(mr).isEmpty(), "Commit messages failed")
        assertTrue(crossValidator.validate(mr).isEmpty(), "Cross-entity consistency failed")
        assertTrue(langValidator.validate(mr).isEmpty(), "Language policy failed")
        assertTrue(jiraValidator.validate(mr).isEmpty(), "Jira ticket failed")
    }

    @Test
    fun `full invalid mr multiple violations`() {
        val titleValidator = TitleFormatValidator(TitleFormatConfig(pattern = TEL_TITLE_PATTERN, minLength = 10))
        val langValidator = LanguagePolicyValidator(LanguagePolicyConfig())

        val mr = createTestMergeRequest(
            title = "Hi",
            description = null,
            sourceBranch = "bugfix",
            commits = listOf(
                createCommit("aaa1111", "fix stuff"),
                createCommit("bbb2222", "\u0418\u0441\u043f\u0440\u0430\u0432\u0438\u0442\u044c \u0431\u0430\u0433"),
            ),
        )

        assertTrue(titleValidator.validate(mr).isNotEmpty(), "Should fail title format")
        assertTrue(langValidator.validate(mr).isNotEmpty(), "Should fail language policy")
    }
}
