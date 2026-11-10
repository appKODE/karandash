package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests validating WAL rule behavior in isolation.
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

private const val WAL_TITLE_PATTERN = "^(\\[[A-Z]+-\\d+\\])?\\[(android|ios)\\] .+"

// =========================================================================
// TITLE FORMAT (MR + Commit)
// Format: [WAL-123][android] Title text (NO space between ticket and tag)
// =========================================================================

class WalletTitleFormatTest {
    @Test
    fun `title with task id and android tag passes`() {
        val config = TitleFormatConfig(pattern = WAL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-456][android] Add card details screen")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `title with task id and ios tag passes`() {
        val config = TitleFormatConfig(pattern = WAL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-789][ios] Fix OTP timer issue")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `title without task id but with tag passes`() {
        val config = TitleFormatConfig(pattern = WAL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[ios] Add feature without task ID")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `title with space between ticket and tag fails`() {
        val config = TitleFormatConfig(pattern = WAL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-456] [android] Space between ticket and tag")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
    }

    @Test
    fun `title without platform tag fails`() {
        val config = TitleFormatConfig(pattern = WAL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-456] Missing platform tag entirely")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
    }

    @Test
    fun `title with invalid platform tag fails`() {
        val config = TitleFormatConfig(pattern = WAL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-456][backend] Wrong platform tag")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
    }

    @Test
    fun `title without text after tag fails`() {
        val config = TitleFormatConfig(pattern = WAL_TITLE_PATTERN, minLength = 10)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-456][ios]")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
    }
}

// =========================================================================
// COMMIT MESSAGE FORMAT
// Same format as MR title: [WAL-123][android] Description
// =========================================================================

class WalletCommitMessagesTest {
    @Test
    fun `commit messages with correct format pass`() {
        val config = CommitMessagesConfig(pattern = WAL_TITLE_PATTERN)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[WAL-456][android] Implement card details UI"),
                createCommit("bbb2222", "[WAL-456][android] Add unit tests"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `commit messages without task id pass`() {
        val config = CommitMessagesConfig(pattern = WAL_TITLE_PATTERN)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[ios] Fix without task ID"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `plain text commit message fails`() {
        val config = CommitMessagesConfig(pattern = WAL_TITLE_PATTERN)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "just a plain commit message"),
            ),
        )
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
    }
}

// =========================================================================
// CONTEXT TAG VALIDATION
// Exactly one of: [android], [ios]
// =========================================================================

class WalletContextTagTest {
    @Test
    fun `context tag android passes`() {
        val config = ContextTagConfig(allowed = listOf("android", "ios"), exactlyOne = true)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-456][android] Feature")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `context tag ios passes`() {
        val config = ContextTagConfig(allowed = listOf("android", "ios"), exactlyOne = true)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-789][ios] Feature")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `no tag fails`() {
        val config = ContextTagConfig(allowed = listOf("android", "ios"), exactlyOne = true)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-456] Feature without context")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("No context tag"))
    }

    @Test
    fun `two tags fail`() {
        val config = ContextTagConfig(allowed = listOf("android", "ios"), exactlyOne = true)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-456][android][ios] Both platforms")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Multiple context tags"))
    }

    @Test
    fun `invalid tag fails`() {
        val config = ContextTagConfig(allowed = listOf("android", "ios"), exactlyOne = true)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-456][backend] Wrong tag")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
    }
}

// =========================================================================
// COMMIT BODY
// CHANGELOG: [WAL-123] Task name. (android-only via conditional)
// =========================================================================

class WalletCommitBodyTest {
    @Test
    fun `changelog line correct`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: (\\[WAL-\\d+\\] .+)(, \\[WAL-\\d+\\] .+)*\\.$",
            required = true,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[WAL-456][android] Add feature\n\nCHANGELOG: [WAL-456] Add card details screen."),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `multiple tasks in changelog correct`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: (\\[WAL-\\d+\\] .+)(, \\[WAL-\\d+\\] .+)*\\.$",
            required = true,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit(
                    "aaa1111",
                    "[WAL-456][WAL-789][android] Work\n\nCHANGELOG: [WAL-456] Card details, [WAL-789] Transaction history.",
                ),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `missing body fails`() {
        val config = CommitBodyConfig(required = true)
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[WAL-456][android] Title only, no body"),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("body is required") || it.message.contains("Commit body is required") })
    }

    @Test
    fun `wrong format fails`() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: (\\[WAL-\\d+\\] .+)(, \\[WAL-\\d+\\] .+)*\\.$",
            required = true,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[WAL-456][android] Feature\n\nJust some text instead of CHANGELOG"),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("does not match") })
    }
}

// =========================================================================
// CROSS-ENTITY CONSISTENCY
// =========================================================================

class WalletCrossEntityConsistencyTest {
    @Test
    fun `commit subset of mr passes`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[WAL-456][WAL-789][android] Multi-task MR",
            commits = listOf(
                createCommit("aaa1111", "[WAL-456][android] Part 1"),
                createCommit("bbb2222", "[WAL-789][android] Part 2"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `commit has extra task fails`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[WAL-456][android] Single task MR",
            commits = listOf(
                createCommit("aaa1111", "[WAL-456][WAL-999][android] Commit references extra task"),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("WAL-999") })
    }

    @Test
    fun `changelog count within mr passes`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[WAL-456][WAL-789][android] Feature",
            commits = listOf(
                createCommit("aaa1111", "[WAL-456][android] Work\n\nCHANGELOG: [WAL-456] Task one."),
                createCommit("bbb2222", "[WAL-789][android] Work\n\nCHANGELOG: [WAL-789] Task two."),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `changelog has extra tasks fails`() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[WAL-456][android] Single task",
            commits = listOf(
                createCommit(
                    "aaa1111",
                    "[WAL-456][android] Work\n\nCHANGELOG: [WAL-456] Task one, [WAL-999] Sneaky extra task.",
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

class WalletLanguagePolicyTest {
    @Test
    fun `all english passes`() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[WAL-456][android] Add card details screen",
            description = "Added card details screen.",
            sourceBranch = "feature/WAL-456-card-details",
            commits = listOf(
                createCommit("aaa1111", "[WAL-456][android] Implement card details"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `cyrillic in title fails`() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[WAL-456][android] \u0414\u043e\u0431\u0430\u0432\u0438\u0442\u044c \u043a\u0430\u0440\u0442\u0443",
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
            title = "[WAL-456][android] Fix bug",
            sourceBranch = "feature/test",
            commits = listOf(
                createCommit(
                    "aaa1111",
                    "[WAL-456][android] \u0418\u0441\u043f\u0440\u0430\u0432\u0438\u0442\u044c \u043e\u0448\u0438\u0431\u043a\u0443"
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
            title = "[WAL-456][android] Update cafe resume feature",
            sourceBranch = "feature/test",
        )
        assertTrue(validator.validate(mr).isEmpty())
    }
}

// =========================================================================
// JIRA TICKET VALIDATION
// =========================================================================

class WalletJiraTicketTest {
    @Test
    fun `correct project key passes`() {
        val config = JiraTicketConfig(projectKeys = listOf("WAL"))
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "[WAL-456][android] Feature")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun `wrong project key fails`() {
        val config = JiraTicketConfig(projectKeys = listOf("WAL"))
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123][android] Wrong project")
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("Invalid JIRA project key") })
    }
}

// =========================================================================
// FULL MR EXAMPLES
// =========================================================================

class WalletFullMrValidationTest {
    @Test
    fun `full valid android mr all rules pass`() {
        val titlePattern = WAL_TITLE_PATTERN

        val titleValidator = TitleFormatValidator(TitleFormatConfig(pattern = titlePattern, minLength = 10))
        val commitValidator = CommitMessagesValidator(CommitMessagesConfig(pattern = titlePattern))
        val tagValidator = ContextTagValidator(
            ContextTagConfig(allowed = listOf("android", "ios"), exactlyOne = true),
        )
        val crossValidator = CrossEntityConsistencyValidator(CrossEntityConsistencyConfig())
        val langValidator = LanguagePolicyValidator(LanguagePolicyConfig())
        val jiraValidator = JiraTicketValidator(JiraTicketConfig(projectKeys = listOf("WAL")))

        val mr = createTestMergeRequest(
            title = "[WAL-456][android] Add card details screen",
            description = "Added card details screen with transaction history.",
            sourceBranch = "feature/WAL-456-card-details",
            commits = listOf(
                createCommit("aaa1111", "[WAL-456][android] Implement card details\n\nCHANGELOG: [WAL-456] Card details screen."),
            ),
        )

        assertTrue(titleValidator.validate(mr).isEmpty(), "Title format failed")
        assertTrue(commitValidator.validate(mr).isEmpty(), "Commit messages failed")
        assertTrue(tagValidator.validate(mr).isEmpty(), "Context tag failed")
        assertTrue(crossValidator.validate(mr).isEmpty(), "Cross-entity consistency failed")
        assertTrue(langValidator.validate(mr).isEmpty(), "Language policy failed")
        assertTrue(jiraValidator.validate(mr).isEmpty(), "Jira ticket failed")
    }

    @Test
    fun `full invalid mr multiple violations`() {
        val titlePattern = WAL_TITLE_PATTERN

        val titleValidator = TitleFormatValidator(TitleFormatConfig(pattern = titlePattern, minLength = 10))
        val commitValidator = CommitMessagesValidator(CommitMessagesConfig(pattern = titlePattern))
        val tagValidator = ContextTagValidator(
            ContextTagConfig(allowed = listOf("android", "ios"), exactlyOne = true),
        )
        val langValidator = LanguagePolicyValidator(LanguagePolicyConfig())

        val mr = createTestMergeRequest(
            title = "Fix bug",
            description = null,
            sourceBranch = "bugfix",
            commits = listOf(
                createCommit("aaa1111", "fix stuff"),
                createCommit("bbb2222", "\u0418\u0441\u043f\u0440\u0430\u0432\u0438\u0442\u044c \u0431\u0430\u0433"),
            ),
        )

        assertTrue(titleValidator.validate(mr).isNotEmpty(), "Should fail title format")
        assertTrue(commitValidator.validate(mr).isNotEmpty(), "Should fail commit messages")
        assertTrue(tagValidator.validate(mr).isNotEmpty(), "Should fail context tag")
        assertTrue(langValidator.validate(mr).isNotEmpty(), "Should fail language policy")
    }
}
