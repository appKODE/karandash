package io.karandash.plugins.ai.core.architecture

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdrValidatorTest {
    private fun makeMr(changes: List<FileChange>) =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = null,
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User(username = "dev", name = "Dev"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = listOf(Commit("sha1", "init", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z")),
            changes = changes,
        )

    private fun change(
        path: String,
        diff: String? = null
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = diff)

    // region no ADRs

    @Test
    fun noAdrsReturnsEmpty() {
        val mr = makeMr(listOf(change("src/Foo.kt", "+System.getenv(\"SECRET\")")))
        val findings = AdrValidator.validate(mr, emptyList())
        assertTrue(findings.isEmpty())
    }

    // endregion

    // region no affected files

    @Test
    fun noAffectedFilesReturnsEmpty() {
        val mr = makeMr(listOf(change("src/Foo.kt", "+some code")))
        val adr = AdrConstraint(
            id = "ADR-001",
            name = "No direct DB access",
            affectedPaths = listOf("src/controllers/**"),
            forbiddenPatterns = listOf("DatabaseConnection"),
        )
        val findings = AdrValidator.validate(mr, listOf(adr))
        assertTrue(findings.isEmpty())
    }

    // endregion

    // region forbidden patterns

    @Test
    fun forbiddenPatternViolationDetected() {
        val mr = makeMr(listOf(change("src/controllers/UserController.kt", "+DatabaseConnection.query()")))
        val adr = AdrConstraint(
            id = "ADR-001",
            name = "No direct DB access",
            affectedPaths = listOf("src/controllers/**"),
            forbiddenPatterns = listOf("DatabaseConnection"),
            rationale = "Controllers must use repository layer",
        )
        val findings = AdrValidator.validate(mr, listOf(adr))
        assertEquals(1, findings.size)
        val f = findings.first()
        assertTrue(f.message.contains("ADR-001") || f.message.contains("No direct DB access"))
        assertTrue(f.message.contains("DatabaseConnection"))
        assertEquals("adr:ADR-001", f.rule)
        assertEquals("Controllers must use repository layer", f.explanation)
    }

    @Test
    fun allowedPatternOnTheSameLineExemptsForbiddenMatch() {
        val diff = "+runBlocking { } // bridge-boundary: cli\n+fun x() = runBlocking { }"
        val adr = AdrConstraint(
            id = "ADR-003",
            name = "Coroutines only",
            affectedPaths = listOf("src/**"),
            forbiddenPatterns = listOf("runBlocking"),
            allowedPatterns = listOf("bridge-boundary"),
        )

        val exemptOnly = AdrValidator.validate(makeMr(listOf(change("src/A.kt", diff.substringBefore("\n")))), listOf(adr))
        val mixed = AdrValidator.validate(makeMr(listOf(change("src/A.kt", diff))), listOf(adr))

        assertTrue(exemptOnly.isEmpty())
        assertEquals(1, mixed.size)
    }

    @Test
    fun forbiddenPatternNotPresentProducesNoFinding() {
        val mr = makeMr(listOf(change("src/controllers/UserController.kt", "+userRepository.findById(id)")))
        val adr = AdrConstraint(
            id = "ADR-001",
            name = "No direct DB access",
            affectedPaths = listOf("src/controllers/**"),
            forbiddenPatterns = listOf("DatabaseConnection"),
        )
        val findings = AdrValidator.validate(mr, listOf(adr))
        assertTrue(findings.isEmpty())
    }

    @Test
    fun forbiddenPatternFileWithNullDiffSkipped() {
        val mr = makeMr(listOf(change("src/controllers/UserController.kt", null)))
        val adr = AdrConstraint(
            id = "ADR-001",
            name = "No direct DB access",
            affectedPaths = listOf("src/controllers/**"),
            forbiddenPatterns = listOf("DatabaseConnection"),
        )
        val findings = AdrValidator.validate(mr, listOf(adr))
        assertTrue(findings.isEmpty())
    }

    // endregion

    // region required patterns

    @Test
    fun requiredPatternMissingDetected() {
        val mr = makeMr(listOf(change("src/api/Handler.kt", "+fun handle() {}")))
        val adr = AdrConstraint(
            id = "ADR-002",
            name = "All handlers must log",
            affectedPaths = listOf("src/api/**"),
            requiredPatterns = listOf("logger.info"),
        )
        val findings = AdrValidator.validate(mr, listOf(adr))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("logger.info"))
        assertEquals("adr:ADR-002", findings.first().rule)
    }

    @Test
    fun requiredPatternPresentProducesNoFinding() {
        val mr = makeMr(listOf(change("src/api/Handler.kt", "+fun handle() { logger.info(\"called\") }")))
        val adr = AdrConstraint(
            id = "ADR-002",
            name = "All handlers must log",
            affectedPaths = listOf("src/api/**"),
            requiredPatterns = listOf("logger.info"),
        )
        val findings = AdrValidator.validate(mr, listOf(adr))
        assertTrue(findings.isEmpty())
    }

    // endregion

    // region glob matching

    @Test
    fun globDoubleStarMatchesNestedPath() {
        assertTrue(AdrValidator.matchesGlob("src/controllers/v1/UserController.kt", "src/controllers/**"))
    }

    @Test
    fun globSingleStarDoesNotMatchSlash() {
        val matched = AdrValidator.matchesGlob("src/Foo.kt", "src/*.kt")
        assertTrue(matched)
        val notMatched = AdrValidator.matchesGlob("src/sub/Foo.kt", "src/*.kt")
        assertTrue(!notMatched)
    }

    @Test
    fun globExactExtensionMatch() {
        assertTrue(AdrValidator.matchesGlob("src/Foo.kt", "**/*.kt"))
        assertTrue(!AdrValidator.matchesGlob("src/Foo.java", "**/*.kt"))
    }

    // endregion

    // region severity from ADR config

    @Test
    fun severityErrorAppliedFromAdrConfig() {
        val mr = makeMr(listOf(change("src/Foo.kt", "+banned")))
        val adr = AdrConstraint(
            id = "ADR-003",
            name = "Critical rule",
            affectedPaths = listOf("src/**"),
            forbiddenPatterns = listOf("banned"),
            severity = "error",
        )
        val findings = AdrValidator.validate(mr, listOf(adr))
        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings.first().severity)
    }

    @Test
    fun severityDefaultsToWarningForUnknownValue() {
        val mr = makeMr(listOf(change("src/Foo.kt", "+banned")))
        val adr = AdrConstraint(
            id = "ADR-004",
            name = "Rule",
            affectedPaths = listOf("src/**"),
            forbiddenPatterns = listOf("banned"),
            severity = "unknown-level",
        )
        val findings = AdrValidator.validate(mr, listOf(adr))
        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings.first().severity)
    }

    // endregion

    // region rationale in explanation

    @Test
    fun rationaleIncludedInFindingExplanation() {
        val mr = makeMr(listOf(change("src/Foo.kt", "+banned")))
        val adr = AdrConstraint(
            id = "ADR-005",
            name = "Rule",
            affectedPaths = listOf("src/**"),
            forbiddenPatterns = listOf("banned"),
            rationale = "Because we said so",
        )
        val findings = AdrValidator.validate(mr, listOf(adr))
        assertEquals(1, findings.size)
        assertEquals("Because we said so", findings.first().explanation)
    }

    @Test
    fun emptyRationaleProducesNullExplanation() {
        val mr = makeMr(listOf(change("src/Foo.kt", "+banned")))
        val adr = AdrConstraint(
            id = "ADR-006",
            name = "Rule",
            affectedPaths = listOf("src/**"),
            forbiddenPatterns = listOf("banned"),
            rationale = "",
        )
        val findings = AdrValidator.validate(mr, listOf(adr))
        assertEquals(1, findings.size)
        assertEquals(null, findings.first().explanation)
    }

    // endregion
}
