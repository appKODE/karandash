package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.text.CharCategory

class LanguagePolicyValidatorTest {
    private val defaultConfig = LanguagePolicyConfig()

    @Test
    fun testEnglishTextPasses() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Add new feature",
            description = "TASK: https://jira.example.com/MAPS-123\n\nCHANGELOG: [MAPS-123] Added feature.",
            sourceBranch = "feature/MAPS-123-add-feature",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCyrillicInTitleFails() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Добавить функцию",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.severity == Severity.ERROR && it.message.contains("MR title") })
    }

    @Test
    fun testCyrillicInDescriptionFails() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Add feature",
            description = "Описание на русском языке",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("MR description") })
    }

    @Test
    fun testCyrillicInCommitTitleFails() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val commit = createCommit(message = "[MAPS-123][ios] Исправить баг")
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Fix bug",
            sourceBranch = "feature/test",
            commits = listOf(commit),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.startsWith("Commit") && it.message.contains("subject") })
    }

    @Test
    fun testCyrillicInCommitDescriptionFails() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val commit = createCommit(message = "[MAPS-123][ios] Fix bug\n\nОписание коммита")
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Fix bug",
            sourceBranch = "feature/test",
            commits = listOf(commit),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.startsWith("Commit") && it.message.contains("body") })
    }

    @Test
    fun testChineseCharactersFail() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] 添加功能",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty())
    }

    @Test
    fun testArabicCharactersFail() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] إضافة ميزة",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty())
    }

    @Test
    fun testLatinAccentedCharactersPass() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Add café résumé naïve",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Accented Latin chars should pass: $findings")
    }

    @Test
    fun testDigitsAndPunctuationPass() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Fix bug #456 (v2.0.1) - 100% done!",
            sourceBranch = "feature/MAPS-123-fix-456",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testDisableChecksPasses() {
        val config = LanguagePolicyConfig(
            checkMrTitle = false,
            checkMrDescription = false,
            checkCommitTitle = false,
            checkCommitDescription = false,
            checkBranchName = false,
        )
        val validator = LanguagePolicyValidator(config)
        val commit = createCommit(message = "Коммит на русском\n\nОписание")
        val mr = createTestMergeRequest(
            title = "Заголовок",
            description = "Описание",
            sourceBranch = "ветка/фича",
            commits = listOf(commit),
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testNullDescriptionSkipsCheck() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Feature",
            description = null,
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `commit subjects not in the language fold into one finding`() {
        val commits = (1..3).map { createCommit(sha = "cccc00$it", message = "Исправить $it") }
        val mr = createTestMergeRequest(title = "Fix", sourceBranch = "feature/test", commits = commits)

        assertEquals(
            listOf("3 commits have a subject not in English (cccc001, cccc002, cccc003)"),
            LanguagePolicyValidator(defaultConfig).validate(mr).map { it.message },
        )
    }

    @Test
    fun `a single commit names its script`() {
        val mr = createTestMergeRequest(
            title = "Fix",
            sourceBranch = "feature/test",
            commits = listOf(createCommit(sha = "7d40815aa", message = "Ελληνικά и русский")),
        )

        assertEquals(
            listOf("Commit 7d40815 subject is not in English (Greek and Cyrillic found)"),
            LanguagePolicyValidator(defaultConfig).validate(mr).map { it.message },
        )
    }

    @Test
    fun testErrorMessageIncludesCharacterDetails() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Добавить",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty())
        assertEquals("MR title is not in English (Cyrillic found)", findings[0].message)
    }

    @Test
    fun `a Latin-only policy runs the findNonLatinLetters helper`() {
        val latinOnly = LanguagePolicyValidator(LanguagePolicyConfig(allowedScripts = listOf("latin")))
        val text = "Hello Мир ß ẞ"
        assertEquals(LanguagePolicyValidator.findNonLatinLetters(text), latinOnly.findDisallowedLetters(text))
        assertEquals(listOf('М', 'и', 'р'), LanguagePolicyValidator.findNonLatinLetters(text))
    }

    /** the Latin-only check is the one `findDisallowedLetters` under a Latin config, not a copy of it. */
    @Test
    fun `non-Latin letters come from the one disallowed-letters check`() {
        val findNonLatinLetters = LanguagePolicyValidator(LanguagePolicyConfig(allowedScripts = listOf("latin")))::findDisallowedLetters
        val latin = findNonLatinLetters("Hello World 123!")
        assertTrue(latin.isEmpty())

        val cyrillic = findNonLatinLetters("Привет")
        assertTrue(cyrillic.isNotEmpty())
        assertTrue(cyrillic.contains('П'))

        val mixed = findNonLatinLetters("Hello Мир")
        assertEquals(3, mixed.size)
        assertTrue(mixed.all { !it.code.let { c -> c in 0x0041..0x024F } })
    }

    // --- Configurable scripts tests ---

    @Test
    fun testCyrillicAllowedConfigPassesCyrillicText() {
        val config = LanguagePolicyConfig(allowedScripts = listOf("latin", "cyrillic"), languageName = "English/Russian")
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Добавить функцию",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Cyrillic should pass when allowed: $findings")
    }

    @Test
    fun testCyrillicOnlyConfigRejectsLatinText() {
        val config = LanguagePolicyConfig(allowedScripts = listOf("cyrillic"), languageName = "Russian")
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "Hello World",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty(), "Latin should fail when only cyrillic is allowed")
        assertEquals(true, findings[0].suggestion?.contains("Russian"))
    }

    @Test
    fun testMultipleScriptsAllowed() {
        val config = LanguagePolicyConfig(allowedScripts = listOf("latin", "cyrillic", "greek"))
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "Hello Мир αβγ",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Mixed latin/cyrillic/greek should pass: $findings")
    }

    @Test
    fun testCustomUnicodeRangeAllowed() {
        val config = LanguagePolicyConfig(
            allowedScripts = listOf("latin"),
            additionalAllowedRanges = listOf("0400-04FF"),
        )
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Привет feature",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Cyrillic via custom range should pass: $findings")
    }

    @Test
    fun testInvalidCustomUnicodeRangeWarns() {
        val config = LanguagePolicyConfig(additionalAllowedRanges = listOf("0400-04FF", "zz-04FF", "0400"))
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(title = "[MAPS-123][ios] Add feature", sourceBranch = "feature/test")

        val findings = validator.validate(mr)
        assertEquals(1, findings.size, "Only the invalid-range warning expected: $findings")
        assertEquals(Severity.WARNING, findings[0].severity)
        assertTrue(findings[0].message.contains("zz-04FF, 0400"), findings[0].message)
    }

    @Test
    fun testLanguageNameInErrorMessage() {
        val config = LanguagePolicyConfig(languageName = "Spanish")
        val validator = LanguagePolicyValidator(config)
        val mr = createTestMergeRequest(
            title = "[MAPS-123][ios] Добавить",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isNotEmpty())
        assertEquals(true, findings[0].suggestion?.contains("Spanish"), "Suggestion should mention configured language name")
    }

    @Test
    fun testScriptRangesMapContainsAllNamedScripts() {
        val expectedScripts = listOf("latin", "cyrillic", "greek", "arabic", "cjk", "hebrew", "devanagari")
        expectedScripts.forEach { script ->
            assertTrue(LanguagePolicyValidator.SCRIPT_RANGES.containsKey(script), "Missing script range: $script")
        }
    }

    // --- Non-prose content stripping tests ---

    @Test
    fun testCyrillicFilenameInImageAttachmentPasses() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Add screenshot",
            description = "See attachment:\n![Снимок экрана.png](/uploads/abc123/Снимок экрана.png)",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Cyrillic filename in image attachment should pass: $findings")
    }

    @Test
    fun testCyrillicInFileLinkUrlPasses() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Add document",
            description = "See [document.pdf](/uploads/abc123/документ.pdf) for details.",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Cyrillic in file link URL (not display text) should pass: $findings")
    }

    @Test
    fun testCyrillicInsideFencedCodeBlockPasses() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Add example",
            description = "Example:\n```\nval привет = \"world\"\n```",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Cyrillic inside fenced code block should pass: $findings")
    }

    @Test
    fun testCyrillicInsideInlineCodePasses() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Fix issue",
            description = "The variable `привет` was renamed.",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Cyrillic inside inline code should pass: $findings")
    }

    @Test
    fun testCyrillicInBareUrlPasses() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Add link",
            description = "See https://example.com/путь/к/ресурсу for reference.",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Cyrillic in bare URL should pass: $findings")
    }

    @Test
    fun testCyrillicInsideHtmlCommentPasses() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Fix bug",
            description = "Fixed the issue.\n<!--待处理: review needed -->",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty(), "Non-Latin inside HTML comment should pass: $findings")
    }

    @Test
    fun testCyrillicInLinkDisplayTextStillFails() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Add link",
            description = "See [документация](https://example.com/docs) for more info.",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("MR description") }, "Cyrillic in link display text should still fail: $findings")
    }

    @Test
    fun testCyrillicProseInDescriptionStillFails() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Fix bug",
            description = "Это описание на русском языке.",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(findings.any { it.message.contains("MR description") }, "Cyrillic prose in description should still fail: $findings")
    }

    @Test
    fun testFencedCodeBlockWithCyrillicAndProseOutsideBlock() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Fix bug",
            description = "```\nval привет = 1\n```\n\nОписание на русском.",
            sourceBranch = "feature/test",
        )

        val findings = validator.validate(mr)
        assertTrue(
            findings.any { it.message.contains("MR description") },
            "Only prose Cyrillic outside code block should be flagged: $findings"
        )
    }

    @Test
    fun testCommitBodyWithCodeSnippetAndCyrillicProse() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val commit = createCommit(message = "[MAPS-123] Fix bug\n\nFix applied:\n```\nval x = привет\n```\n\nОписание коммита на русском.")
        val mr = createTestMergeRequest(
            title = "[MAPS-123] Fix bug",
            sourceBranch = "feature/test",
            commits = listOf(commit),
        )

        val findings = validator.validate(mr)
        assertTrue(
            findings.any {
                it.message.startsWith("Commit") && it.message.contains("body")
            },
            "Only prose Cyrillic in commit body should be flagged: $findings"
        )
    }

    // --- Locale-independent letter detection (must work under linuxX64 LANG=C) ---

    @Test
    fun testCyrillicCharsDetectedAsLettersViaCharCategory() {
        val cyrillicChars = listOf('д', 'Д', 'ж', 'Ж', 'я', 'Я')
        cyrillicChars.forEach { ch ->
            val isLetter = ch.category in listOf(
                CharCategory.UPPERCASE_LETTER,
                CharCategory.LOWERCASE_LETTER,
            )
            assertTrue(isLetter, "CharCategory must classify Cyrillic '$ch' (U+${ch.code.toString(16)}) as a letter")
        }
    }

    @Test
    fun testFindDisallowedLettersDetectsCyrillicBranchName() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val result = validator.findDisallowedLetters("feature/добавить-экран")
        assertTrue(result.isNotEmpty(), "Must detect Cyrillic letters in branch name")
        assertTrue(result.any { it.code in 0x0400..0x04FF }, "Detected chars must be in Cyrillic range")
    }

    @Test
    fun testFindDisallowedLettersAllScriptRanges() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val samples = mapOf(
            "cyrillic" to "Привет",
            "greek" to "αβγ",
            "arabic" to "مرحبا",
            "cjk" to "你好",
            "hebrew" to "שלום",
            "devanagari" to "नमस्ते",
        )
        samples.forEach { (script, text) ->
            val result = validator.findDisallowedLetters(text)
            assertTrue(result.isNotEmpty(), "Must detect $script letters as disallowed in latin-only mode")
        }
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

    @Test
    fun `file content in another language is never a finding while a Cyrillic title still is`() {
        val validator = LanguagePolicyValidator(defaultConfig)
        val changelog = FileChange(
            path = "CHANGELOG.md",
            changeType = ChangeType.MODIFIED,
            additions = 1,
            diffContent = "@@ -1 +1,2 @@\n # История изменений\n+- Исправлена ошибка входа",
            newContent = "# История изменений\n- Исправлена ошибка входа\n",
        )
        val english = createTestMergeRequest(title = "Fix login", sourceBranch = "fix/login", changes = listOf(changelog))

        assertEquals(emptyList(), validator.validate(english))
        assertTrue(validator.validate(english.copy(title = "Исправить вход")).any { "MR title" in it.message })
    }
}
