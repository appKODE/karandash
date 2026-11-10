package io.karandash.validators

import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.User
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.sdk.config.BlockMode
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileChangesValidatorTest {
    @Test
    fun testGlobalBlockOnDraft() {
        val config = FileChangesConfig(
            blockOnDraft = BlockMode.FAIL
        )
        val validator = FileChangesValidator(config)

        val draftMr = createTestMergeRequest(draft = true)
        val nonDraftMr = createTestMergeRequest(draft = false)

        val draftFindings = validator.validate(draftMr)
        assertEquals(1, draftFindings.size)
        assertEquals(Severity.ERROR, draftFindings[0].severity)
        assertTrue(draftFindings[0].message.contains("Draft"))

        val nonDraftFindings = validator.validate(nonDraftMr)
        assertTrue(nonDraftFindings.isEmpty())
    }

    @Test
    fun testPatternMatchingWithGlobs() {
        val config = FileChangesConfig(
            patterns = listOf(
                FilePatternRule(
                    pattern = "*.md",
                    requireLabels = listOf("documentation")
                )
            )
        )
        val validator = FileChangesValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange("README.md", ChangeType.MODIFIED),
                FileChange("src/main.kt", ChangeType.MODIFIED)
            ),
            labels = emptyList()
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("README.md"))
        assertTrue(findings[0].message.contains("documentation"))
    }

    @Test
    fun testPatternMatchingWithDoubleStarGlobs() {
        val config = FileChangesConfig(
            patterns = listOf(
                FilePatternRule(
                    pattern = "src/**/*.kt",
                    requireLabels = listOf("code-change")
                )
            )
        )
        val validator = FileChangesValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange("src/main/kotlin/Main.kt", ChangeType.MODIFIED),
                FileChange("src/test/kotlin/Test.kt", ChangeType.MODIFIED),
                FileChange("README.md", ChangeType.MODIFIED)
            ),
            labels = emptyList()
        )

        val findings = validator.validate(mr)
        assertEquals(2, findings.size) // Both .kt files should trigger
        assertTrue(findings.all { it.message.contains("code-change") })
    }

    @Test
    fun testRequireApprovalFromPattern() {
        val config = FileChangesConfig(
            patterns = listOf(
                FilePatternRule(
                    pattern = "database/**",
                    requireApproval = listOf("dba-team", "tech-lead")
                )
            )
        )
        val validator = FileChangesValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange("database/schema.sql", ChangeType.MODIFIED)
            )
        )

        val findings = validator.validate(mr)
        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertTrue(findings[0].message.contains("dba-team"))
        assertTrue(findings[0].message.contains("tech-lead"))
    }

    @Test
    fun testRequireLabelsWithMissingLabels() {
        val config = FileChangesConfig(
            patterns = listOf(
                FilePatternRule(
                    pattern = "src/security/**",
                    requireLabels = listOf("security-review", "approved")
                )
            )
        )
        val validator = FileChangesValidator(config)

        val mrWithNoLabels = createTestMergeRequest(
            changes = listOf(
                FileChange("src/security/Auth.kt", ChangeType.MODIFIED)
            ),
            labels = emptyList()
        )

        val mrWithPartialLabels = createTestMergeRequest(
            changes = listOf(
                FileChange("src/security/Auth.kt", ChangeType.MODIFIED)
            ),
            labels = listOf("security-review")
        )

        val mrWithAllLabels = createTestMergeRequest(
            changes = listOf(
                FileChange("src/security/Auth.kt", ChangeType.MODIFIED)
            ),
            labels = listOf("security-review", "approved")
        )

        val findingsNoLabels = validator.validate(mrWithNoLabels)
        assertEquals(1, findingsNoLabels.size)
        assertEquals(Severity.ERROR, findingsNoLabels[0].severity)
        assertTrue(findingsNoLabels[0].message.contains("security-review"))
        assertTrue(findingsNoLabels[0].message.contains("approved"))

        val findingsPartial = validator.validate(mrWithPartialLabels)
        assertEquals(1, findingsPartial.size)
        assertTrue(findingsPartial[0].message.contains("approved"))

        val findingsAll = validator.validate(mrWithAllLabels)
        assertTrue(findingsAll.isEmpty())
    }

    @Test
    fun testBlockOnDraftPerPattern() {
        val config = FileChangesConfig(
            patterns = listOf(
                FilePatternRule(
                    pattern = "production/**",
                    blockOnDraft = BlockMode.FAIL
                )
            )
        )
        val validator = FileChangesValidator(config)

        val draftMr = createTestMergeRequest(
            draft = true,
            changes = listOf(
                FileChange("production/config.yml", ChangeType.MODIFIED)
            )
        )

        val nonDraftMr = createTestMergeRequest(
            draft = false,
            changes = listOf(
                FileChange("production/config.yml", ChangeType.MODIFIED)
            )
        )

        val draftFindings = validator.validate(draftMr)
        assertEquals(1, draftFindings.size)
        assertEquals(Severity.ERROR, draftFindings[0].severity)
        assertTrue(draftFindings[0].message.contains("production/config.yml"))
        assertTrue(draftFindings[0].message.contains("draft"))

        val nonDraftFindings = validator.validate(nonDraftMr)
        assertTrue(nonDraftFindings.isEmpty())
    }

    @Test
    fun testGlobalRequireApproval() {
        val config = FileChangesConfig(
            requireApproval = listOf("team-lead", "senior-dev")
        )
        val validator = FileChangesValidator(config)

        val mrWithChanges = createTestMergeRequest(
            changes = listOf(
                FileChange("src/main.kt", ChangeType.MODIFIED)
            )
        )

        val mrWithoutChanges = createTestMergeRequest(
            changes = emptyList()
        )

        val findingsWithChanges = validator.validate(mrWithChanges)
        assertEquals(1, findingsWithChanges.size)
        assertEquals(Severity.WARNING, findingsWithChanges[0].severity)
        assertTrue(findingsWithChanges[0].message.contains("team-lead"))

        val findingsWithoutChanges = validator.validate(mrWithoutChanges)
        assertTrue(findingsWithoutChanges.isEmpty())
    }

    @Test
    fun testGlobalRequireLabels() {
        val config = FileChangesConfig(
            requireLabels = listOf("reviewed", "tested")
        )
        val validator = FileChangesValidator(config)

        val mrWithoutLabels = createTestMergeRequest(labels = emptyList())
        val mrWithSomeLabels = createTestMergeRequest(labels = listOf("reviewed"))
        val mrWithAllLabels = createTestMergeRequest(labels = listOf("reviewed", "tested"))

        val findingsNoLabels = validator.validate(mrWithoutLabels)
        assertEquals(1, findingsNoLabels.size)
        assertEquals(Severity.ERROR, findingsNoLabels[0].severity)
        assertTrue(findingsNoLabels[0].message.contains("reviewed"))
        assertTrue(findingsNoLabels[0].message.contains("tested"))

        val findingsSome = validator.validate(mrWithSomeLabels)
        assertEquals(1, findingsSome.size)
        assertTrue(findingsSome[0].message.contains("tested"))

        val findingsAll = validator.validate(mrWithAllLabels)
        assertTrue(findingsAll.isEmpty())
    }

    @Test
    fun testMultiplePatternRules() {
        val config = FileChangesConfig(
            patterns = listOf(
                FilePatternRule(
                    pattern = "*.md",
                    requireLabels = listOf("documentation")
                ),
                FilePatternRule(
                    pattern = "src/**/*.kt",
                    requireLabels = listOf("code-review")
                ),
                FilePatternRule(
                    pattern = "database/**",
                    requireApproval = listOf("dba")
                )
            )
        )
        val validator = FileChangesValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange("README.md", ChangeType.MODIFIED),
                FileChange("src/main.kt", ChangeType.MODIFIED),
                FileChange("database/schema.sql", ChangeType.MODIFIED)
            ),
            labels = emptyList()
        )

        val findings = validator.validate(mr)
        // Should have findings for README.md, src/main.kt (missing labels), and database/schema.sql (approval warning)
        assertTrue(findings.size >= 3)
    }

    @Test
    fun testNoValidationWhenNoPatternsOrRules() {
        val config = FileChangesConfig()
        val validator = FileChangesValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(
                FileChange("any-file.txt", ChangeType.MODIFIED)
            )
        )

        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testPatternFindingsIncludeFilePath() {
        val config = FileChangesConfig(
            patterns = listOf(
                FilePatternRule(
                    pattern = "database/**",
                    requireApproval = listOf("dba-team")
                ),
                FilePatternRule(
                    pattern = "src/security/**",
                    requireLabels = listOf("security-review")
                ),
                FilePatternRule(
                    pattern = "production/**",
                    blockOnDraft = BlockMode.FAIL
                )
            )
        )
        val validator = FileChangesValidator(config)

        val mr = createTestMergeRequest(
            draft = true,
            changes = listOf(
                FileChange("database/schema.sql", ChangeType.MODIFIED),
                FileChange("src/security/Auth.kt", ChangeType.MODIFIED),
                FileChange("production/config.yml", ChangeType.MODIFIED)
            ),
            labels = emptyList()
        )

        val findings = validator.validate(mr)

        val approvalFinding = findings.first { it.message.contains("requires approval") }
        val loc1 = approvalFinding.location as Location.FileRef
        assertEquals("database/schema.sql", loc1.path)
        assertNull(loc1.line)

        val labelFinding = findings.first { it.message.contains("requires labels") }
        val loc2 = labelFinding.location as Location.FileRef
        assertEquals("src/security/Auth.kt", loc2.path)
        assertNull(loc2.line)

        val draftFinding = findings.first { it.message.contains("blocked in draft") }
        val loc3 = draftFinding.location as Location.FileRef
        assertEquals("production/config.yml", loc3.path)
        assertNull(loc3.line)
    }

    @Test
    fun testGlobalFindingsHaveNoFileLocation() {
        val config = FileChangesConfig(
            requireApproval = listOf("team-lead"),
            requireLabels = listOf("reviewed")
        )
        val validator = FileChangesValidator(config)

        val mr = createTestMergeRequest(
            changes = listOf(FileChange("src/main.kt", ChangeType.MODIFIED)),
            labels = emptyList()
        )

        val findings = validator.validate(mr)
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.location == null })
    }

    @Test
    fun testGlobPatternWithDots() {
        val config = FileChangesConfig(
            patterns = listOf(
                FilePatternRule(
                    pattern = "src/main.kt",
                    requireLabels = listOf("main-file")
                )
            )
        )
        val validator = FileChangesValidator(config)

        val mrMatch = createTestMergeRequest(
            changes = listOf(
                FileChange("src/main.kt", ChangeType.MODIFIED)
            ),
            labels = emptyList()
        )

        val mrNoMatch = createTestMergeRequest(
            changes = listOf(
                FileChange("src/mainXkt", ChangeType.MODIFIED)
            ),
            labels = emptyList()
        )

        val findingsMatch = validator.validate(mrMatch)
        assertEquals(1, findingsMatch.size)

        val findingsNoMatch = validator.validate(mrNoMatch)
        assertTrue(findingsNoMatch.isEmpty())
    }

    /** A protected path needs an approval from someone other than the MR author. */
    @Test
    fun `requireNonAuthorApproval rejects self approval only`() {
        val validator = FileChangesValidator(
            FileChangesConfig(patterns = listOf(FilePatternRule(pattern = "**/*.pem", requireNonAuthorApproval = true))),
        )
        val change = listOf(FileChange("keys/a.pem", ChangeType.MODIFIED))

        val selfApproved = createTestMergeRequest(
            changes = change,
            approvals = listOf(Approval(User(username = "testuser", name = "Test User"))),
        )
        val otherApproved = createTestMergeRequest(
            changes = change,
            approvals = listOf(Approval(User(username = "bob", name = "Bob"))),
        )

        assertEquals(1, validator.validate(selfApproved).size)
        assertTrue(validator.validate(otherApproved).isEmpty())
        assertEquals(1, validator.validate(createTestMergeRequest(changes = change)).size)
    }
}
