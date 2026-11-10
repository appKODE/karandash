package io.karandash.engine

import io.karandash.api.context.ConditionContext
import io.karandash.conditions.CommonPredicates
import io.karandash.conditions.ConditionPredicate
import io.karandash.conditions.ExpressionEvaluator
import io.karandash.conditions.MutablePredicateRegistry
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.patterns.PatternCompilationResult
import io.karandash.patterns.TextGlobCompiler
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end tests for the condition expression evaluator with [CommonPredicates]
 * and test-local inline predicates for module-owned predicates.
 *
 * Dedicated predicate tests live in their owning modules:
 * - `file_path_matches`, `has_changes_in` → file-changes/commonTest
 * - `label_exists` → labels/commonTest
 * - `has_ticket` → jira-ticket/commonTest
 * - `has_tag` → context-tag/commonTest
 * - `rule_has_findings`, `rule_passed`, `rule_failed` → conditional/commonTest
 */
class MrConditionEvaluatorTest {
    // ── Test helpers ──────────────────────────────────────────────────────

    private val globCompiler = TextGlobCompiler()

    private fun makeMR(
        title: String = "feat: add feature",
        sourceBranch: String = "feature/my-branch",
        targetBranch: String = "main",
        author: String = "alice",
        draft: Boolean = false,
        labels: List<String> = emptyList(),
        changes: List<FileChange> = emptyList(),
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = title,
            description = null,
            author = User(username = author, name = author),
            sourceBranch = sourceBranch,
            targetBranch = targetBranch,
            state = MergeRequestState.OPENED,
            draft = draft,
            labels = labels,
            commits = emptyList(),
            changes = changes,
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun fileChange(
        path: String,
        additions: Int = 0,
    ) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = additions)

    private fun finding(
        ruleId: String,
        severity: Severity = Severity.ERROR,
    ) = Finding(severity = severity, message = "msg", rule = ruleId)

    private fun eval(
        expression: String,
        mr: MergeRequest = makeMR(),
        findings: List<Finding> = emptyList(),
    ) = buildEvaluator(mr, findings).evaluate(expression)

    private fun buildEvaluator(
        mr: MergeRequest,
        findings: List<Finding>,
    ): ExpressionEvaluator<ConditionContext> {
        val context = ConditionContext(mr = mr, priorFindings = findings)
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry)
        registerTestPredicates(registry)
        val ev = ExpressionEvaluator(context)
        ev.registerAll(registry)
        return ev
    }

    private fun registerTestPredicates(registry: MutablePredicateRegistry<ConditionContext>) {
        registry.register(
            pred("file_path_matches", 1) { args, ctx ->
                val glob = args.getOrNull(0) ?: return@pred false
                ctx.mr.changes.any {
                    val r = globCompiler.compile(glob)
                    r is PatternCompilationResult.Success && r.regex.containsMatchIn(it.path)
                }
            }
        )
        registry.register(
            pred("has_changes_in", 1) { args, ctx ->
                val prefix = args.getOrNull(0) ?: return@pred false
                ctx.mr.changes.any { it.path.startsWith(prefix) }
            }
        )
        registry.register(
            pred("label_exists", 1) { args, ctx ->
                val label = args.getOrNull(0) ?: return@pred false
                ctx.mr.labels.any { it.equals(label, ignoreCase = true) }
            }
        )
        registry.register(
            pred("rule_has_findings", 1) { args, ctx ->
                val ruleId = args.getOrNull(0) ?: return@pred false
                ctx.priorFindings.any { it.rule == ruleId }
            }
        )
        registry.register(
            pred("rule_passed", 1) { args, ctx ->
                val ruleId = args.getOrNull(0) ?: return@pred false
                ctx.priorFindings.none { it.rule == ruleId && it.severity == Severity.ERROR }
            }
        )
        registry.register(
            pred("rule_failed", 1) { args, ctx ->
                val ruleId = args.getOrNull(0) ?: return@pred false
                ctx.priorFindings.any { it.rule == ruleId && it.severity == Severity.ERROR }
            }
        )
    }

    private fun pred(
        predName: String,
        predArity: Int,
        fn: (List<String>, ConditionContext) -> Boolean,
    ): ConditionPredicate<ConditionContext> =
        object : ConditionPredicate<ConditionContext> {
            override val name: String = predName
            override val arity: Int = predArity

            override fun evaluate(
                args: List<String>,
                context: ConditionContext
            ): Boolean = fn(args, context)
        }

    // ── is_draft ──────────────────────────────────────────────────────────

    @Test
    fun `is_draft returns true when draft`() {
        assertTrue(eval("is_draft", makeMR(draft = true)))
    }

    @Test
    fun `is_draft returns false when not draft`() {
        assertFalse(eval("is_draft", makeMR(draft = false)))
    }

    @Test
    fun `is_draft-false returns true when not draft`() {
        assertTrue(eval("is_draft(false)", makeMR(draft = false)))
    }

    @Test
    fun `is_draft-true returns false when not draft`() {
        assertFalse(eval("is_draft(true)", makeMR(draft = false)))
    }

    // ── branch_matches ────────────────────────────────────────────────────

    @Test
    fun `branch_matches matches source branch by default`() {
        assertTrue(eval("branch_matches('feature/.*')", makeMR(sourceBranch = "feature/my-branch")))
    }

    @Test
    fun `branch_matches with target matches target branch`() {
        assertTrue(eval("branch_matches('main', target)", makeMR(targetBranch = "main")))
    }

    @Test
    fun `branch_matches returns false when source branch does not match`() {
        assertFalse(eval("branch_matches('^hotfix/.*')", makeMR(sourceBranch = "feature/foo")))
    }

    @Test
    fun `branch_matches with target returns false when target branch does not match`() {
        assertFalse(eval("branch_matches('develop', target)", makeMR(targetBranch = "main")))
    }

    // ── author_matches ────────────────────────────────────────────────────

    @Test
    fun `author_matches returns true when username matches regex`() {
        assertTrue(eval("author_matches('alice')", makeMR(author = "alice")))
    }

    @Test
    fun `author_matches returns false when username does not match`() {
        assertFalse(eval("author_matches('^bob$')", makeMR(author = "alice")))
    }

    // ── title_matches ─────────────────────────────────────────────────────

    @Test
    fun `title_matches returns true when title contains match`() {
        assertTrue(eval("title_matches('feat:')", makeMR(title = "feat: add login")))
    }

    @Test
    fun `title_matches returns false when title does not match`() {
        assertFalse(eval("title_matches('^fix:')", makeMR(title = "feat: add login")))
    }

    // ── change_count ──────────────────────────────────────────────────────

    @Test
    fun `change_count greater-than returns true when count exceeds threshold`() {
        val mr = makeMR(changes = listOf(fileChange("a.kt"), fileChange("b.kt"), fileChange("c.kt")))
        assertTrue(eval("change_count > 2", mr))
    }

    @Test
    fun `change_count greater-than returns false when count is equal`() {
        val mr = makeMR(changes = listOf(fileChange("a.kt"), fileChange("b.kt")))
        assertFalse(eval("change_count > 2", mr))
    }

    @Test
    fun `change_count greater-than-or-equal returns true when count equals threshold`() {
        val mr = makeMR(changes = listOf(fileChange("a.kt"), fileChange("b.kt")))
        assertTrue(eval("change_count >= 2", mr))
    }

    @Test
    fun `change_count less-than returns true when count is below threshold`() {
        val mr = makeMR(changes = listOf(fileChange("a.kt")))
        assertTrue(eval("change_count < 5", mr))
    }

    @Test
    fun `change_count less-than-or-equal returns true when count equals threshold`() {
        val mr = makeMR(changes = listOf(fileChange("a.kt")))
        assertTrue(eval("change_count <= 1", mr))
    }

    @Test
    fun `change_count equals returns true when count matches`() {
        val mr = makeMR(changes = listOf(fileChange("a.kt"), fileChange("b.kt")))
        assertTrue(eval("change_count == 2", mr))
    }

    // ── additions ────────────────────────────────────────────────────────

    @Test
    fun `additions greater-than returns true when total additions exceed threshold`() {
        val mr = makeMR(changes = listOf(fileChange("a.kt", additions = 60), fileChange("b.kt", additions = 50)))
        assertTrue(eval("additions > 100", mr))
    }

    @Test
    fun `additions greater-than returns false when total additions are below threshold`() {
        val mr = makeMR(changes = listOf(fileChange("a.kt", additions = 40)))
        assertFalse(eval("additions > 100", mr))
    }

    @Test
    fun `additions equals returns true when sum matches exactly`() {
        val mr = makeMR(changes = listOf(fileChange("a.kt", additions = 50), fileChange("b.kt", additions = 50)))
        assertTrue(eval("additions == 100", mr))
    }

    // ── not() ─────────────────────────────────────────────────────────────

    @Test
    fun `not negates a true predicate`() {
        assertFalse(eval("not(is_draft)", makeMR(draft = true)))
    }

    @Test
    fun `not negates a false predicate`() {
        assertTrue(eval("not(is_draft)", makeMR(draft = false)))
    }

    @Test
    fun `bang prefix negates bare and call and grouped predicates`() {
        assertTrue(eval("!is_draft", makeMR(draft = false)))
        assertFalse(eval("!is_draft", makeMR(draft = true)))
        assertTrue(eval("label_exists('bug') && !is_draft", makeMR(labels = listOf("bug"))))
        assertTrue(eval("!label_exists('bug')", makeMR(labels = emptyList())))
        assertFalse(eval("!(is_draft || label_exists('bug'))", makeMR(labels = listOf("bug"))))
    }

    @Test
    fun `not wrapping label_exists`() {
        assertTrue(eval("not(label_exists('bug'))", makeMR(labels = emptyList())))
    }

    // ── && operator ───────────────────────────────────────────────────────

    @Test
    fun `AND returns true when all parts are true`() {
        val mr = makeMR(draft = false, labels = listOf("urgent"))
        assertTrue(eval("not(is_draft) && label_exists('urgent')", mr))
    }

    @Test
    fun `AND returns false when first part is false`() {
        val mr = makeMR(draft = true, labels = listOf("urgent"))
        assertFalse(eval("not(is_draft) && label_exists('urgent')", mr))
    }

    @Test
    fun `AND returns false when second part is false`() {
        val mr = makeMR(draft = false, labels = emptyList())
        assertFalse(eval("not(is_draft) && label_exists('urgent')", mr))
    }

    @Test
    fun `AND chains three predicates`() {
        val mr = makeMR(
            draft = false,
            labels = listOf("bug"),
            changes = listOf(fileChange("src/Foo.kt")),
        )
        assertTrue(eval("not(is_draft) && label_exists('bug') && file_path_matches('**/*.kt')", mr))
    }

    // ── || operator ───────────────────────────────────────────────────────

    @Test
    fun `OR returns true when first part is true`() {
        assertTrue(eval("is_draft || label_exists('urgent')", makeMR(draft = true)))
    }

    @Test
    fun `OR returns true when second part is true`() {
        assertTrue(eval("is_draft || label_exists('urgent')", makeMR(draft = false, labels = listOf("urgent"))))
    }

    @Test
    fun `OR returns false when both parts are false`() {
        assertFalse(eval("is_draft || label_exists('urgent')", makeMR(draft = false)))
    }

    // ── Operator precedence ───────────────────────────────────────────────

    @Test
    fun `OR has lower precedence than AND`() {
        val mr = makeMR(draft = false, labels = listOf("bug"))
        assertFalse(eval("is_draft || label_exists('bug') && label_exists('urgent')", mr))
    }

    @Test
    fun `parentheses override precedence`() {
        val mr = makeMR(draft = false, labels = listOf("bug"))
        assertTrue(eval("(is_draft || label_exists('bug')) && not(label_exists('urgent'))", mr))
    }

    // ── Nested parentheses ────────────────────────────────────────────────

    @Test
    fun `deeply nested parentheses evaluate correctly`() {
        val mr = makeMR(draft = true, labels = listOf("urgent"))
        assertTrue(eval("((is_draft && label_exists('urgent')))", mr))
    }

    @Test
    fun `not wrapping AND expression`() {
        val mr = makeMR(draft = false, labels = listOf("bug"))
        assertTrue(eval("not(is_draft && label_exists('bug'))", mr))
    }

    @Test
    fun `not wrapping OR expression`() {
        val mr = makeMR(draft = false, labels = emptyList())
        assertTrue(eval("not(is_draft || label_exists('urgent'))", mr))
    }

    // ── Error cases ───────────────────────────────────────────────────────

    @Test
    fun `unknown predicate throws IllegalStateException`() {
        assertFailsWith<IllegalStateException> {
            eval("unknown_predicate('foo')")
        }
    }

    @Test
    fun `expression too long throws IllegalArgumentException`() {
        val longExpr = "is_draft".repeat(200)
        assertFailsWith<IllegalArgumentException> {
            eval(longExpr)
        }
    }

    @Test
    fun `invalid comparison operator throws IllegalStateException`() {
        assertFailsWith<IllegalStateException> {
            eval("change_count != 5")
        }
    }

    @Test
    fun `non-integer comparison value throws IllegalStateException`() {
        assertFailsWith<IllegalStateException> {
            eval("change_count > abc")
        }
    }

    @Test
    fun `regex pattern exceeding max length throws IllegalArgumentException`() {
        val longPattern = "a".repeat(201)
        assertFailsWith<IllegalArgumentException> {
            eval("author_matches('$longPattern')")
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────

    @Test
    fun `expression with surrounding whitespace is trimmed`() {
        assertTrue(eval("  is_draft  ", makeMR(draft = true)))
    }

    @Test
    fun `additions returns false on MR with no changes`() {
        assertFalse(eval("additions > 0", makeMR()))
    }

    @Test
    fun `combined findings-aware and MR predicates`() {
        val mr = makeMR(labels = listOf("security"))
        val findings = listOf(finding("security-scan", Severity.ERROR))
        assertTrue(eval("label_exists('security') && rule_failed('security-scan')", mr, findings))
    }

    // ── Nesting depth guard ───────────────────────────────────────────────

    @Test
    fun `deeply nested parentheses exceeding MAX_NESTING_DEPTH throws`() {
        val nested = "(".repeat(51) + "is_draft" + ")".repeat(51)
        assertFailsWith<IllegalArgumentException> {
            eval(nested)
        }
    }

    @Test
    fun `50 levels of nesting is accepted`() {
        val nested = "(".repeat(50) + "is_draft" + ")".repeat(50)
        eval(nested, mr = makeMR(draft = true))
    }

    // ── Comma inside quoted args ──────────────────────────────────────────

    @Test
    fun `branch_matches with comma inside quoted pattern`() {
        val mr = makeMR(sourceBranch = "feat/a,b")
        assertTrue(eval("""branch_matches("feat/a,b", "source")""", mr))
    }

    @Test
    fun `branch_matches with comma in arg does not split incorrectly`() {
        val mr = makeMR(sourceBranch = "feat/a,b-test")
        assertTrue(eval("""branch_matches("feat/a,b-test", "source")""", mr))
    }
}
