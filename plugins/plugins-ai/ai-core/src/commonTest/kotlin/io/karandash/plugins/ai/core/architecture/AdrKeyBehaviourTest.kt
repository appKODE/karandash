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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One behaviour test per `ai.adrs` key. */
class AdrKeyBehaviourTest {
    private fun mr(vararg changes: FileChange) =
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
            changes = changes.toList(),
        )

    private fun change(
        path: String,
        diff: String,
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1, deletions = 0, diffContent = diff)

    private val base = AdrConstraint(id = "ADR-7", name = "No db", affectedPaths = listOf("src/ctl/**"), forbiddenPatterns = listOf("Db."))

    @Test
    fun `adrs affectedPaths limits the check to matching files`() {
        val request = mr(change("src/ctl/A.kt", "+Db.q()"), change("src/other/B.kt", "+Db.q()"))
        val findings = AdrValidator.validate(request, listOf(base))
        assertEquals(1, findings.size)
        assertTrue(findings.single().message.endsWith("src/ctl/A.kt"))
    }

    @Test
    fun `adrs forbiddenPatterns reports a diff that contains the pattern`() {
        val findings = AdrValidator.validate(mr(change("src/ctl/A.kt", "+Db.q()")), listOf(base))
        assertEquals("ADR 'No db' violated: forbidden pattern 'Db.' found in src/ctl/A.kt", findings.single().message)
    }

    @Test
    fun `adrs allowedPatterns exempts a forbidden match on the same line`() {
        val adr = base.copy(allowedPatterns = listOf("// ok"))
        val request = mr(change("src/ctl/A.kt", "+Db.q() // ok"))
        assertTrue(AdrValidator.validate(request, listOf(adr)).isEmpty())
        assertEquals(1, AdrValidator.validate(mr(change("src/ctl/A.kt", "+Db.q()")), listOf(adr)).size)
    }

    @Test
    fun `adrs requiredPatterns reports a missing pattern in the affected files`() {
        val adr = AdrConstraint(id = "ADR-8", name = "Audit", affectedPaths = listOf("src/ctl/**"), requiredPatterns = listOf("audit("))
        val missing = AdrValidator.validate(mr(change("src/ctl/A.kt", "+run()")), listOf(adr))
        assertEquals("ADR 'Audit': required pattern 'audit(' not found in affected files", missing.single().message)
        assertTrue(AdrValidator.validate(mr(change("src/ctl/A.kt", "+audit(x)")), listOf(adr)).isEmpty())
    }

    @Test
    fun `adrs id names the finding rule`() {
        val findings = AdrValidator.validate(mr(change("src/ctl/A.kt", "+Db.q()")), listOf(base.copy(id = "ADR-42")))
        assertEquals("adr:ADR-42", findings.single().rule)
    }

    @Test
    fun `adrs name is quoted in the finding message`() {
        val findings = AdrValidator.validate(mr(change("src/ctl/A.kt", "+Db.q()")), listOf(base.copy(name = "Layering")))
        assertTrue(findings.single().message.startsWith("ADR 'Layering' violated"))
    }

    @Test
    fun `adrs rationale becomes the finding explanation`() {
        val withRationale = AdrValidator.validate(mr(change("src/ctl/A.kt", "+Db.q()")), listOf(base.copy(rationale = "Keep layers apart")))
        assertEquals("Keep layers apart", withRationale.single().explanation)
        assertNull(AdrValidator.validate(mr(change("src/ctl/A.kt", "+Db.q()")), listOf(base)).single().explanation)
    }

    @Test
    fun `adrs severity sets the finding severity`() {
        val request = mr(change("src/ctl/A.kt", "+Db.q()"))
        assertEquals(Severity.ERROR, AdrValidator.validate(request, listOf(base.copy(severity = "error"))).single().severity)
        assertEquals(Severity.INFO, AdrValidator.validate(request, listOf(base.copy(severity = "info"))).single().severity)
    }
}
