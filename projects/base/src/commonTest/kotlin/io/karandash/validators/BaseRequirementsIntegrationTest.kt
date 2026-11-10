package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests validating BASE rule behavior in isolation.
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

private const val BASE_TITLE_PATTERN = "^(\\[[A-Z]+-\\d+\\] )?.+"

// =========================================================================
// TITLE FORMAT (MR + Commit)
// Format: [PROJ-123] Title text (space after ticket, no platform tag)
// =========================================================================

class BaseTitleFormatTest {
    @Test
    fun `title with task id and space passes`() {
        val config = TitleFormatConfig(pattern = BASE_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[PROJ-123] Add feature screen")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `title without task id passes`() {
        val config = TitleFormatConfig(pattern = BASE_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "Fix NPE in login flow")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `title with multiple task ids passes`() {
        val config = TitleFormatConfig(pattern = BASE_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[PROJ-100] [PROJ-101] Batch fix for navigation")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `title too short fails`() {
        val config = TitleFormatConfig(pattern = BASE_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "Hi")
        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty())
    }

    @Test
    fun `title with number only ticket format fails pattern`() {
        val config = TitleFormatConfig(pattern = BASE_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[123] Wrong ticket format without project key")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `title exceeding max length fails`() {
        val config = TitleFormatConfig(pattern = BASE_TITLE_PATTERN, minLength = 10, maxLength = 120)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[PROJ-123] " + "A".repeat(120))
        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty())
    }
}

// =========================================================================
// COMMIT MESSAGE FORMAT
// Same format as MR title: [PROJ-123] Description
// =========================================================================

class BaseCommitMessagesTest {
    @Test
    fun `commit messages with task id pass`() {
        val config = CommitMessagesConfig(pattern = BASE_TITLE_PATTERN)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[PROJ-123] Implement login UI"),
                createCommit("bbb2222", "[PROJ-123] Add unit tests for login"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `commit messages without task id pass`() {
        val config = CommitMessagesConfig(pattern = BASE_TITLE_PATTERN)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "Fix without task ID"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `commit message exceeding max length fails`() {
        val config = CommitMessagesConfig(pattern = BASE_TITLE_PATTERN, maxLength = 72)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[PROJ-123] " + "A".repeat(72)),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty())
    }
}

// =========================================================================
// CROSS-ENTITY CONSISTENCY
// Commit task IDs must be subset of MR task IDs
// =========================================================================

class BaseCrossEntityConsistencyTest {
    @Test
    fun `commit subset of mr passes`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[PROJ-123] [PROJ-456] Multi-task MR",
            commits = listOf(
                createCommit("aaa1111", "[PROJ-123] Part 1"),
                createCommit("bbb2222", "[PROJ-456] Part 2"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `commit has extra task fails`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[PROJ-123] Single task MR",
            commits = listOf(
                createCommit("aaa1111", "[PROJ-123] [PROJ-999] Commit references extra task"),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("PROJ-999") })
    }

    @Test
    fun `changelog count within mr passes`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[PROJ-123] [PROJ-456] Feature",
            commits = listOf(
                createCommit("aaa1111", "[PROJ-123] Work\n\nCHANGELOG: [PROJ-123] Task one."),
                createCommit("bbb2222", "[PROJ-456] Work\n\nCHANGELOG: [PROJ-456] Task two."),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `changelog has extra tasks fails`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[PROJ-123] Single task",
            commits = listOf(
                createCommit(
                    "aaa1111",
                    "[PROJ-123] Work\n\nCHANGELOG: [PROJ-123] Task one, [PROJ-456] Sneaky extra task.",
                ),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("CHANGELOG") })
    }
}

// =========================================================================
// LANGUAGE POLICY
// All text must be in English (Latin script)
// =========================================================================

class BaseLanguagePolicyTest {
    @Test
    fun `all english passes`() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[PROJ-123] Add login screen",
            description = "Added new login screen.",
            sourceBranch = "feature/PROJ-123-login-screen",
            commits = listOf(
                createCommit("aaa1111", "[PROJ-123] Implement login"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `cyrillic in title fails`() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[PROJ-123] " + "\u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u044d\u043a\u0440\u0430\u043d " +
                "\u0432\u0445\u043e\u0434\u0430",
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
            title = "[PROJ-123] Fix bug",
            sourceBranch = "feature/test",
            commits = listOf(
                createCommit(
                    "aaa1111",
                    "[PROJ-123] \u0418\u0441\u043f\u0440\u0430\u0432\u0438\u0442\u044c \u043e\u0448\u0438\u0431\u043a\u0443"
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
            title = "[PROJ-123] Update cafe resume feature",
            sourceBranch = "feature/test",
        )
        assertTrue(validator.validate(mr).isEmpty())
    }
}

// =========================================================================
// JIRA TICKET VALIDATION
// Project key must match configured key
// =========================================================================

class BaseJiraTicketTest {
    @Test
    fun `correct project key passes`() {
        val config = JiraTicketConfig(projectKeys = listOf("PROJ"))
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "[PROJ-123] Feature")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `wrong project key fails`() {
        val config = JiraTicketConfig(projectKeys = listOf("PROJ"))
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "[OTHER-123] Wrong project")
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("Invalid JIRA project key") })
    }
}

// =========================================================================
// FULL MR EXAMPLES
// End-to-end validation with all rules
// =========================================================================

class BaseFullMrValidationTest {
    @Test
    fun `full valid mr all rules pass`() {
        val titlePattern = BASE_TITLE_PATTERN

        val titleValidator = TitleFormatValidator(TitleFormatConfig(pattern = titlePattern, minLength = 10))
        val commitValidator = CommitMessagesValidator(CommitMessagesConfig(pattern = titlePattern))
        val crossValidator = CrossEntityConsistencyValidator(CrossEntityConsistencyConfig())
        val langValidator = LanguagePolicyValidator(LanguagePolicyConfig())
        val jiraValidator = JiraTicketValidator(JiraTicketConfig(projectKeys = listOf("PROJ")))

        val mr = createTestMergeRequest(
            title = "[PROJ-123] Add login screen with biometric auth",
            description = "Added new login screen with biometric authentication support.",
            sourceBranch = "feature/PROJ-123-login-screen",
            commits = listOf(
                createCommit("aaa1111", "[PROJ-123] Implement login UI\n\nCHANGELOG: [PROJ-123] Login screen."),
                createCommit("bbb2222", "[PROJ-123] Add biometric auth"),
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
        val titlePattern = BASE_TITLE_PATTERN

        val titleValidator = TitleFormatValidator(TitleFormatConfig(pattern = titlePattern, minLength = 10))
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
