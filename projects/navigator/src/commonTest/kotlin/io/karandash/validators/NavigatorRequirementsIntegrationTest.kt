package io.karandash.validators

import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests validating NAV rule behavior in isolation.
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

private const val NAV_TITLE_PATTERN_OPTIONAL_TASK = "^((\\[NAV-\\d+\\])+)?(\\[(ios|android|core)\\]) .+"

// =========================================================================
// TITLE FORMAT (MR + Commit)
// Format: [NAV-{id}]...[ContextTag] Title text
// =========================================================================

class MapsTitleFormatTest {
    @Test
    fun titleFormat_singleTaskId_correct() {
        val config = TitleFormatConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123][ios] Add login screen")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun titleFormat_multipleTaskIds_correct() {
        val config = TitleFormatConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123][NAV-456][android] Refactor navigation")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun titleFormat_coreContextTag_correct() {
        val config = TitleFormatConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-789][core] Update shared models")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun titleFormat_noTaskId_correct() {
        val config = TitleFormatConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[ios] Add feature without task ID")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun titleFormat_noContextTag_incorrect() {
        val config = TitleFormatConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123] Missing context tag")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
    }

    @Test
    fun titleFormat_invalidContextTag_incorrect() {
        val config = TitleFormatConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123][backend] Wrong tag")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
    }

    @Test
    fun titleFormat_contextTagBeforeTaskId_incorrect() {
        val config = TitleFormatConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[ios][NAV-123] Tag before task ID")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
    }

    @Test
    fun titleFormat_noTitleText_incorrect() {
        val config = TitleFormatConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
        val validator = TitleFormatValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123][ios]")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
    }
}

// =========================================================================
// COMMIT MESSAGE FORMAT
// Same format as MR title: [NAV-{id}]...[ContextTag] Title
// =========================================================================

class MapsCommitMessagesTest {
    @Test
    fun commitMessages_correctFormat() {
        val config = CommitMessagesConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[NAV-123][ios] Implement login UI"),
                createCommit("bbb2222", "[NAV-123][ios] Add unit tests for login"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun commitMessages_missingTaskId_correct() {
        val config = CommitMessagesConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
        val validator = CommitMessagesValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[ios] Fix without task ID"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun commitMessages_plainTextMessage_incorrect() {
        val config = CommitMessagesConfig(pattern = NAV_TITLE_PATTERN_OPTIONAL_TASK)
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
// Exactly one of: [ios], [android], [core]
// =========================================================================

class MapsContextTagTest {
    @Test
    fun contextTag_singleIos_correct() {
        val config = ContextTagConfig(allowed = listOf("ios", "android", "core"), exactlyOne = true)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123][ios] Feature")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun contextTag_singleAndroid_correct() {
        val config = ContextTagConfig(allowed = listOf("ios", "android", "core"), exactlyOne = true)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123][android] Feature")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun contextTag_singleCore_correct() {
        val config = ContextTagConfig(allowed = listOf("ios", "android", "core"), exactlyOne = true)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123][core] Feature")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun contextTag_noTag_incorrect() {
        val config = ContextTagConfig(allowed = listOf("ios", "android", "core"), exactlyOne = true)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123] Feature without context")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("No context tag"))
    }

    @Test
    fun contextTag_twoTags_incorrect() {
        val config = ContextTagConfig(allowed = listOf("ios", "android", "core"), exactlyOne = true)
        val validator = ContextTagValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123][ios][android] Both platforms")
        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("Multiple context tags"))
    }
}

// =========================================================================
// COMMIT BODY
// Commit body must start with: CHANGELOG: [NAV-{id}] Task name.
// =========================================================================

class MapsCommitBodyTest {
    @Test
    fun commitBody_changelogLine_correct() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: (\\[NAV-\\d+\\] .+)(, \\[NAV-\\d+\\] .+)*\\.$",
            required = true,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[NAV-123][ios] Add feature\n\nCHANGELOG: [NAV-123] Add login screen."),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun commitBody_multipleTasksInChangelog_correct() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: (\\[NAV-\\d+\\] .+)(, \\[NAV-\\d+\\] .+)*\\.$",
            required = true,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit(
                    "aaa1111",
                    "[NAV-123][NAV-456][ios] Work\n\nCHANGELOG: [NAV-123] Login screen, [NAV-456] Biometric auth.",
                ),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun commitBody_missingBody_incorrect() {
        val config = CommitBodyConfig(
            required = true,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[NAV-123][ios] Title only, no body"),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("body is required") || it.message.contains("Commit body is required") })
    }

    @Test
    fun commitBody_wrongFormat_incorrect() {
        val config = CommitBodyConfig(
            pattern = "^CHANGELOG: (\\[NAV-\\d+\\] .+)(, \\[NAV-\\d+\\] .+)*\\.$",
            required = true,
        )
        val validator = CommitBodyValidator(config)
        val mr = createTestMergeRequest(
            commits = listOf(
                createCommit("aaa1111", "[NAV-123][ios] Feature\n\nJust some text instead of CHANGELOG"),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("does not match") })
    }
}

// =========================================================================
// CROSS-ENTITY CONSISTENCY
// Commit task IDs must be subset of MR task IDs
// CHANGELOG task ID count must not exceed MR task ID count
// =========================================================================

class MapsCrossEntityConsistencyTest {
    @Test
    fun crossEntity_commitSubsetOfMr_correct() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[NAV-123][NAV-456][ios] Multi-task MR",
            commits = listOf(
                createCommit("aaa1111", "[NAV-123][ios] Part 1"),
                createCommit("bbb2222", "[NAV-456][ios] Part 2"),
                createCommit("ccc3333", "[NAV-123][NAV-456][ios] Combined"),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun crossEntity_commitHasExtraTask_incorrect() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[NAV-123][ios] Single task MR",
            commits = listOf(
                createCommit("aaa1111", "[NAV-123][NAV-999][ios] Commit references extra task"),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("NAV-999") })
    }

    @Test
    fun crossEntity_changelogCountWithinMr_correct() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[NAV-123][NAV-456][ios] Feature",
            commits = listOf(
                createCommit("aaa1111", "[NAV-123][ios] Work\n\nCHANGELOG: [NAV-123] Task one."),
                createCommit("bbb2222", "[NAV-456][ios] Work\n\nCHANGELOG: [NAV-456] Task two."),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun crossEntity_changelogHasExtraTasks_incorrect() {
        val config = CrossEntityConsistencyConfig()
        val validator = CrossEntityConsistencyValidator(config)
        val mr = createTestMergeRequest(
            title = "[NAV-123][ios] Single task",
            commits = listOf(
                createCommit(
                    "aaa1111",
                    "[NAV-123][ios] Work\n\nCHANGELOG: [NAV-123] Task one, [NAV-456] Sneaky extra task.",
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

class MapsLanguagePolicyTest {
    @Test
    fun languagePolicy_allEnglish_correct() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[NAV-123][ios] Add login screen",
            description = "TASK: https://jira.example.com/NAV-123\n\nCHANGELOG: [NAV-123] Login screen.",
            sourceBranch = "feature/NAV-123-login-screen",
            commits = listOf(
                createCommit("aaa1111", "[NAV-123][ios] Implement login\n\nCHANGELOG: [NAV-123] Login screen."),
            ),
        )
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun languagePolicy_cyrillicInTitle_incorrect() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[NAV-123][ios] Добавить экран входа",
            sourceBranch = "feature/test",
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("is not in") && it.message.contains("MR title") })
    }

    @Test
    fun languagePolicy_cyrillicInCommit_incorrect() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[NAV-123][ios] Fix bug",
            sourceBranch = "feature/test",
            commits = listOf(
                createCommit("aaa1111", "[NAV-123][ios] Исправить ошибку"),
            ),
        )
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("is not in") && it.message.startsWith("Commit") })
    }

    @Test
    fun languagePolicy_accentedLatinAllowed_correct() {
        val config = LanguagePolicyConfig()
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[NAV-123][ios] Update cafe resume feature",
            sourceBranch = "feature/test",
        )
        assertTrue(validator.validate(mr).isEmpty())
    }
}

// =========================================================================
// JIRA TICKET VALIDATION
// Project key must be NAV
// =========================================================================

class MapsJiraTicketTest {
    @Test
    fun jiraTicket_mapsProject_correct() {
        val config = JiraTicketConfig(projectKeys = listOf("NAV"))
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "[NAV-123][ios] Feature")
        assertTrue(validator.validate(mr).isEmpty())
    }

    @Test
    fun jiraTicket_wrongProject_incorrect() {
        val config = JiraTicketConfig(projectKeys = listOf("NAV"))
        val validator = JiraTicketValidator(config)
        val mr = createTestMergeRequest(title = "[PROJ-123][ios] Wrong project")
        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("Invalid JIRA project key") })
    }
}

// =========================================================================
// FULL MR EXAMPLES
// End-to-end validation with all rules
// =========================================================================

class MapsFullMrValidationTest {
    @Test
    fun fullValidMr_allRulesPass() {
        val titlePattern = NAV_TITLE_PATTERN_OPTIONAL_TASK

        val titleValidator = TitleFormatValidator(TitleFormatConfig(pattern = titlePattern))
        val commitValidator = CommitMessagesValidator(CommitMessagesConfig(pattern = titlePattern))
        val tagValidator = ContextTagValidator(
            ContextTagConfig(allowed = listOf("ios", "android", "core"), exactlyOne = true),
        )
        val crossValidator = CrossEntityConsistencyValidator(CrossEntityConsistencyConfig())
        val langValidator = LanguagePolicyValidator(LanguagePolicyConfig())
        val jiraValidator = JiraTicketValidator(JiraTicketConfig(projectKeys = listOf("NAV")))

        val mr = createTestMergeRequest(
            title = "[NAV-123][NAV-456][ios] Add login screen with biometric",
            description = """
                Added new login screen with biometric authentication support.

                TASK: https://jira.example.com/browse/NAV-123 and https://jira.example.com/browse/NAV-456

                CHANGELOG: [NAV-123] Login screen. [NAV-456] Biometric auth.

                Closes NAV-123 and NAV-456

                Proof: video recording at https://drive.google.com/example

                Labels: ios
            """.trimIndent(),
            sourceBranch = "feature/NAV-123-login-screen",
            commits = listOf(
                createCommit("aaa1111", "[NAV-123][ios] Implement login UI\n\nCHANGELOG: [NAV-123] Login screen."),
                createCommit("bbb2222", "[NAV-456][ios] Add biometric auth\n\nCHANGELOG: [NAV-456] Biometric auth."),
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
    fun fullInvalidMr_multipleViolations() {
        val titlePattern = NAV_TITLE_PATTERN_OPTIONAL_TASK

        val titleValidator = TitleFormatValidator(TitleFormatConfig(pattern = titlePattern))
        val commitValidator = CommitMessagesValidator(CommitMessagesConfig(pattern = titlePattern))
        val tagValidator = ContextTagValidator(
            ContextTagConfig(allowed = listOf("ios", "android", "core"), exactlyOne = true),
        )
        val langValidator = LanguagePolicyValidator(LanguagePolicyConfig())

        val mr = createTestMergeRequest(
            title = "Fix bug",
            description = null,
            sourceBranch = "bugfix",
            commits = listOf(
                createCommit("aaa1111", "fix stuff"),
                createCommit("bbb2222", "Исправить баг"),
            ),
        )

        assertTrue(titleValidator.validate(mr).isNotEmpty(), "Should fail title format")
        assertTrue(commitValidator.validate(mr).isNotEmpty(), "Should fail commit messages")
        assertTrue(tagValidator.validate(mr).isNotEmpty(), "Should fail context tag")
        assertTrue(langValidator.validate(mr).isNotEmpty(), "Should fail language policy")
    }
}
