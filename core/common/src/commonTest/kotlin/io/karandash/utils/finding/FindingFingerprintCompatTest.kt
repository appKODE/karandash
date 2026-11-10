package io.karandash.utils.finding

import io.karandash.models.finding.Location
import io.karandash.models.finding.Location.CommitRef
import io.karandash.models.finding.Location.FileRef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Frozen fingerprints.
 *
 * Fingerprints are persisted (AI feedback dismissals, SARIF baselines, CI dedup across runs), so any change
 * here silently resurrects dismissed findings for every user. If this test fails, the change is a breaking
 * format change: keep the old algorithm or ship a migration, never just update the expected strings.
 */
class FindingFingerprintCompatTest {
    private class Case(
        val rule: String,
        val location: Location?,
        val message: String,
        val expected: String,
    )

    private val cases = listOf(
        Case("title-format", null, "Title too short", "af2f37834c5ed02d"),
        Case("title-format", null, "Title must be at least 10 characters (got 4)", "e32f491f326d43b2"),
        Case(
            "branch-naming",
            null,
            "Branch 'feature/foo' does not match pattern ^(feature|fix)/[A-Z]+-\\d+",
            "eb29228929e00540",
        ),
        Case("description-required", null, "Description is empty", "20d8c752ff0835ec"),
        Case("jira-ticket", null, "No Jira ticket found in title or branch", "d65c3f42b81857a9"),
        Case("max-files", null, "MR changes 128 files, maximum is 50", "c463994c9fa7ced2"),
        Case("max-lines", null, "MR changes   1200 lines,\n maximum is 500", "ee51bcc2ad3b0a52"),
        Case("labels-required", null, "Missing required label: needs-review", "c28b32b0cfa59257"),
        Case("commit-message", CommitRef("a1b2c3d4e5f6"), "Commit message subject exceeds 72 characters", "92a613de2426e572"),
        Case("commit-message", CommitRef("0".repeat(40)), "Commit message is empty", "8a6a557183cea83c"),
        Case("forbidden-files", FileRef("local.properties"), "File local.properties must not be committed", "aa22a142cfc179eb"),
        Case("forbidden-files", FileRef("app/release.keystore"), "Signing keys must not be committed", "c38e07bfee3cf1db"),
        Case("todo-comments", FileRef("src/main/kotlin/App.kt", 42, 44), "TODO without ticket reference", "783404728173d16e"),
        Case("todo-comments", FileRef("src/main/kotlin/App.kt", 0), "TODO without ticket reference", "e59c551af7131002"),
        Case(
            "ai-review",
            FileRef("core/api/src/commonMain/kotlin/Api.kt", 17),
            "Possible null dereference of `config.timeouts`",
            "b5b5a1f268cca857",
        ),
        Case("ai-review", FileRef("cli/Main.kt", 3), "  SQL INJECTION risk:\tuser input concatenated  ", "01921031d1ea81b2"),
        Case("ai-security", FileRef("server/Auth.kt", 88), "Hardcoded secret detected (entropy 4.7)", "4bb1fb1f831220ff"),
        Case("ai-review", FileRef("docs/ru.md", 1), "Опечатка в слове 'конфигурация'", "85363156198bec77"),
        Case("plugin:custom-rule", null, "Custom check failed: value=3.14, limit=2", "46a756fee958ecfa"),
        Case("", null, "", "4a20ee07f2259b33"),
    )

    @Test
    fun `fingerprints match v1_46 values`() {
        val mismatches = cases.mapNotNull { case ->
            val actual = FindingFingerprint.generate(case.rule, case.location, case.message)
            if (actual == case.expected) null else "${case.rule} '${case.message}': expected ${case.expected}, got $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun `null line and line 0 share a fingerprint as in v1_46`() {
        assertEquals(
            FindingFingerprint.generate("todo-comments", FileRef("src/main/kotlin/App.kt", 0), "TODO without ticket reference"),
            FindingFingerprint.generate("todo-comments", FileRef("src/main/kotlin/App.kt"), "TODO without ticket reference"),
        )
    }
}
