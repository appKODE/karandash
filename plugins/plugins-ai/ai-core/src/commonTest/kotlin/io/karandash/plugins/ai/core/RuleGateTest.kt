package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleGateTest {
    private val gate = RuleGate()

    private fun makeMr(
        changes: List<FileChange> = emptyList(),
        labels: List<String> = emptyList(),
        sourceBranch: String = "feature/my-branch",
        commits: List<Commit> = emptyList(),
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test",
        description = null,
        sourceBranch = sourceBranch,
        targetBranch = "main",
        author = User("1", "dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = labels,
        commits = commits,
        changes = changes,
    )

    private fun makeChange(
        path: String,
        additions: Int = 5,
        deletions: Int = 2
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = additions,
        deletions = deletions,
        diffContent = null,
    )

    @Test
    fun `no gate config means rule always runs`() {
        val mr = makeMr(changes = listOf(makeChange("src/main.kt")))
        val result = gate.evaluate("ai-security-scan", mr, emptyList())
        assertNull(result)
    }

    @Test
    fun `file pattern gate skips when no files match`() {
        val mr = makeMr(changes = listOf(makeChange("docs/readme.md")))
        val gates = listOf(
            GateConfig(ruleId = "ai-security-scan", filePatterns = listOf("**/*.kt", "**/*.java")),
        )
        val result = gate.evaluate("ai-security-scan", mr, gates)
        assertNotNull(result)
        assertTrue(result.contains("no files match patterns"))
    }

    @Test
    fun `file pattern gate allows when files match`() {
        val mr = makeMr(changes = listOf(makeChange("src/Main.kt")))
        val gates = listOf(
            GateConfig(ruleId = "ai-security-scan", filePatterns = listOf("**/*.kt")),
        )
        val result = gate.evaluate("ai-security-scan", mr, gates)
        assertNull(result)
    }

    @Test
    fun `minChangedFiles gate skips when too few files`() {
        val mr = makeMr(changes = listOf(makeChange("src/A.kt")))
        val gates = listOf(
            GateConfig(ruleId = "code-suggestions", minChangedFiles = 3),
        )
        val result = gate.evaluate("code-suggestions", mr, gates)
        assertNotNull(result)
        assertTrue(result.contains("too few changed files"))
    }

    @Test
    fun `minChangedFiles gate allows when enough files`() {
        val mr = makeMr(changes = listOf(makeChange("A.kt"), makeChange("B.kt"), makeChange("C.kt")))
        val gates = listOf(
            GateConfig(ruleId = "code-suggestions", minChangedFiles = 3),
        )
        val result = gate.evaluate("code-suggestions", mr, gates)
        assertNull(result)
    }

    @Test
    fun `minChangedLines gate skips when too few lines`() {
        val mr = makeMr(changes = listOf(makeChange("src/A.kt", additions = 2, deletions = 1)))
        val gates = listOf(
            GateConfig(ruleId = "ai-review", minChangedLines = 20),
        )
        val result = gate.evaluate("ai-review", mr, gates)
        assertNotNull(result)
        assertTrue(result.contains("too few changed lines"))
    }

    @Test
    fun `minChangedLines gate allows when enough lines`() {
        val mr = makeMr(changes = listOf(makeChange("src/A.kt", additions = 15, deletions = 10)))
        val gates = listOf(
            GateConfig(ruleId = "ai-review", minChangedLines = 20),
        )
        val result = gate.evaluate("ai-review", mr, gates)
        assertNull(result)
    }

    @Test
    fun `requiredLabels gate skips when label absent`() {
        val mr = makeMr(labels = listOf("bug"))
        val gates = listOf(
            GateConfig(ruleId = "ai-security-scan", requiredLabels = listOf("security", "needs-review")),
        )
        val result = gate.evaluate("ai-security-scan", mr, gates)
        assertNotNull(result)
        assertTrue(result.contains("missing required label"))
    }

    @Test
    fun `requiredLabels gate allows when label present`() {
        val mr = makeMr(labels = listOf("bug", "security"))
        val gates = listOf(
            GateConfig(ruleId = "ai-security-scan", requiredLabels = listOf("security")),
        )
        val result = gate.evaluate("ai-security-scan", mr, gates)
        assertNull(result)
    }

    @Test
    fun `skipLabels gate skips when skip label present`() {
        val mr = makeMr(labels = listOf("chore", "docs-only"))
        val gates = listOf(
            GateConfig(ruleId = "code-suggestions", skipLabels = listOf("docs-only", "skip-ai")),
        )
        val result = gate.evaluate("code-suggestions", mr, gates)
        assertNotNull(result)
        assertTrue(result.contains("skip label present"))
    }

    @Test
    fun `skipLabels gate allows when no skip label present`() {
        val mr = makeMr(labels = listOf("feature"))
        val gates = listOf(
            GateConfig(ruleId = "code-suggestions", skipLabels = listOf("docs-only", "skip-ai")),
        )
        val result = gate.evaluate("code-suggestions", mr, gates)
        assertNull(result)
    }

    @Test
    fun `skipBranchPatterns gate skips when branch matches`() {
        val mr = makeMr(sourceBranch = "chore/update-deps")
        val gates = listOf(
            GateConfig(ruleId = "ai-review", skipBranchPatterns = listOf("chore/", "hotfix/")),
        )
        val result = gate.evaluate("ai-review", mr, gates)
        assertNotNull(result)
        assertTrue(result.contains("branch matches skip pattern"))
    }

    @Test
    fun `skipBranchPatterns gate allows when branch does not match`() {
        val mr = makeMr(sourceBranch = "feature/new-feature")
        val gates = listOf(
            GateConfig(ruleId = "ai-review", skipBranchPatterns = listOf("chore/", "hotfix/")),
        )
        val result = gate.evaluate("ai-review", mr, gates)
        assertNull(result)
    }

    @Test
    fun `gate for different ruleId does not affect other rules`() {
        val mr = makeMr(changes = listOf(makeChange("docs/readme.md")))
        val gates = listOf(
            GateConfig(ruleId = "ai-security-scan", filePatterns = listOf("**/*.kt")),
        )
        val result = gate.evaluate("ai-code-review", mr, gates)
        assertNull(result)
    }

    @Test
    fun `multiple conditions AND together - all pass means rule runs`() {
        val mr = makeMr(
            changes = listOf(makeChange("src/Main.kt", additions = 20, deletions = 5)),
            labels = listOf("feature"),
            sourceBranch = "feature/auth",
        )
        val gates = listOf(
            GateConfig(
                ruleId = "ai-review",
                filePatterns = listOf("**/*.kt"),
                minChangedFiles = 1,
                minChangedLines = 10,
                skipLabels = listOf("chore"),
                skipBranchPatterns = listOf("hotfix/"),
            ),
        )
        val result = gate.evaluate("ai-review", mr, gates)
        assertNull(result)
    }

    @Test
    fun `multiple conditions AND together - one fails means rule is gated`() {
        val mr = makeMr(
            changes = listOf(makeChange("src/Main.kt", additions = 20, deletions = 5)),
            labels = listOf("chore"),
            sourceBranch = "feature/auth",
        )
        val gates = listOf(
            GateConfig(
                ruleId = "ai-review",
                filePatterns = listOf("**/*.kt"),
                minChangedLines = 10,
                skipLabels = listOf("chore"),
            ),
        )
        val result = gate.evaluate("ai-review", mr, gates)
        assertNotNull(result)
        assertTrue(result.contains("skip label present"))
    }

    @Test
    fun `requireLabel gates MR without that label`() {
        val gates = listOf(GateConfig(ruleId = "ai-x", requireLabel = "ready-for-review"))
        assertNotNull(gate.evaluate("ai-x", makeMr(labels = listOf("wip")), gates))
        assertNull(gate.evaluate("ai-x", makeMr(labels = listOf("ready-for-review")), gates))
    }

    @Test
    fun `skipIfTitleMatches skips MR whose title matches the regex`() {
        val gates = listOf(GateConfig(ruleId = "ai-x", skipIfTitleMatches = "^(docs|chore):"))
        val result = gate.evaluate("ai-x", makeMr().copy(title = "docs: fix typo"), gates)
        assertTrue(result.orEmpty().startsWith("title matches skip pattern"))
        assertNull(gate.evaluate("ai-x", makeMr().copy(title = "feat: login"), gates))
    }

    @Test
    fun `invalid skipIfTitleMatches is ignored with a warning`() {
        val warnings = mutableListOf<String>()
        val gates = listOf(GateConfig(ruleId = "ai-x", skipIfTitleMatches = "(unclosed"))
        assertNull(gate.evaluate("ai-x", makeMr(), gates) { warnings += it })
        assertEquals(1, warnings.size)
    }
}
