package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [CommonPredicates], [FieldMatchPredicates], and the operator-syntax
 * desugaring in [ExpressionEvaluator].
 *
 * Predicate tests specific to individual rule modules live in their own test files:
 * - `has_ticket` → jira-ticket/commonTest/…/JiraTicketPredicateTest
 * - `has_tag`    → context-tag/commonTest/…/ContextTagPredicateTest
 */
class BuiltInPredicateRegistryTest {
    private fun makeMr(
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

    private fun evaluator(mr: MergeRequest): ExpressionEvaluator<ConditionContext> {
        val context = ConditionContext(mr = mr)
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry)
        FieldMatchPredicates.forField("branch", { it.mr.sourceBranch }).forEach { registry.register(it) }
        FieldMatchPredicates.forField("title", { it.mr.title }).forEach { registry.register(it) }
        FieldMatchPredicates.forField("author", { it.mr.author.username }).forEach { registry.register(it) }
        val ev = ExpressionEvaluator(context)
        ev.registerAll(registry)
        listOf("branch", "title", "author").forEach { field ->
            ev.registerOperatorField(field, FieldMatchPredicates.operatorNames(field))
        }
        return ev
    }

    // ── operator syntax: title *= (glob) ─────────────────────────────────

    @Test
    fun `title glob operator matches with wildcard`() {
        val ev = evaluator(makeMr(title = "feat: add feature"))
        assertTrue(ev.evaluate("""title *= "feat:*""""))
    }

    @Test
    fun `title glob operator matches alternation`() {
        val ev = evaluator(makeMr(title = "fix: correct bug"))
        assertTrue(ev.evaluate("""title *= "{feat,fix}: *""""))
    }

    @Test
    fun `title glob operator returns false when pattern does not match`() {
        val ev = evaluator(makeMr(title = "chore: update deps"))
        assertFalse(ev.evaluate("""title *= "feat:*""""))
    }

    @Test
    fun `title glob operator equivalence with title_glob_matches function`() {
        val mr = makeMr(title = "feat: add feature")
        val ev = evaluator(mr)
        val opResult = ev.evaluate("""title *= "feat:*"""")
        val fnResult = ev.evaluate("""title_glob_matches("feat:*")""")
        assertEquals(opResult, fnResult)
    }

    // ── operator syntax: title ~= (regex) ────────────────────────────────

    @Test
    fun `title regex operator matches regex pattern`() {
        val ev = evaluator(makeMr(title = "feat: add feature"))
        assertTrue(ev.evaluate("""title ~= "^feat:""""))
    }

    @Test
    fun `title regex operator returns false when no match`() {
        val ev = evaluator(makeMr(title = "chore: update deps"))
        assertFalse(ev.evaluate("""title ~= "^(feat|fix):""""))
    }

    @Test
    fun `title regex operator equivalence with title_matches function`() {
        val mr = makeMr(title = "feat: add feature")
        val ev = evaluator(mr)
        val opResult = ev.evaluate("""title ~= "^feat:"""")
        val fnResult = ev.evaluate("""title_matches("^feat:")""")
        assertEquals(opResult, fnResult)
    }

    // ── operator syntax: title = (exact) ─────────────────────────────────

    @Test
    fun `title exact operator matches exact title`() {
        val ev = evaluator(makeMr(title = "feat: add feature"))
        assertTrue(ev.evaluate("""title = "feat: add feature""""))
    }

    @Test
    fun `title exact operator returns false for partial match`() {
        val ev = evaluator(makeMr(title = "feat: add feature"))
        assertFalse(ev.evaluate("""title = "feat:""""))
    }

    // ── operator syntax: branch operators ────────────────────────────────

    @Test
    fun `branch glob operator matches feature branch`() {
        val ev = evaluator(makeMr(sourceBranch = "feature/my-feature"))
        assertTrue(ev.evaluate("""branch *= "feature/*""""))
    }

    @Test
    fun `branch glob operator returns false when not matching`() {
        val ev = evaluator(makeMr(sourceBranch = "bugfix/my-fix"))
        assertFalse(ev.evaluate("""branch *= "feature/*""""))
    }

    @Test
    fun `branch regex operator matches source branch`() {
        val ev = evaluator(makeMr(sourceBranch = "feature/my-feature"))
        assertTrue(ev.evaluate("""branch ~= "^feature/""""))
    }

    @Test
    fun `branch exact operator matches exact branch name`() {
        val ev = evaluator(makeMr(sourceBranch = "main"))
        assertTrue(ev.evaluate("""branch = "main""""))
    }

    @Test
    fun `branch exact operator returns false for wrong branch`() {
        val ev = evaluator(makeMr(sourceBranch = "feature/my-feature"))
        assertFalse(ev.evaluate("""branch = "main""""))
    }

    // ── operator syntax: author operators ────────────────────────────────

    @Test
    fun `author glob operator matches bot author`() {
        val ev = evaluator(makeMr(author = "dependabot-bot"))
        assertTrue(ev.evaluate("""author *= "*-bot""""))
    }

    @Test
    fun `author glob operator returns false for non-bot`() {
        val ev = evaluator(makeMr(author = "alice"))
        assertFalse(ev.evaluate("""author *= "*-bot""""))
    }

    @Test
    fun `author regex operator matches author by pattern`() {
        val ev = evaluator(makeMr(author = "dependabot[bot]"))
        assertTrue(ev.evaluate("""author ~= "bot\]$""""))
    }

    @Test
    fun `author exact operator matches exact username`() {
        val ev = evaluator(makeMr(author = "alice"))
        assertTrue(ev.evaluate("""author = "alice""""))
    }

    @Test
    fun `author exact operator returns false for different user`() {
        val ev = evaluator(makeMr(author = "bob"))
        assertFalse(ev.evaluate("""author = "alice""""))
    }

    // ── operator syntax: logical combinations ─────────────────────────────

    @Test
    fun `operator syntax works in conjunction with AND`() {
        val ev = evaluator(makeMr(title = "feat: add feature", sourceBranch = "feature/my-feature"))
        assertTrue(ev.evaluate("""title *= "feat:*" && branch *= "feature/*""""))
    }

    @Test
    fun `operator syntax works in disjunction with OR`() {
        val ev = evaluator(makeMr(title = "fix: correct bug"))
        assertTrue(ev.evaluate("""title *= "feat:*" || title *= "fix:*""""))
    }

    // ── registration completeness ────────────────────────────────────────

    @Test
    fun `all expected predicates are registered by CommonPredicates`() {
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry)
        val registered = registry.all().map { it.name }.toSet()

        val expectedPredicates = setOf(
            // CommonPredicates - original
            "is_draft",
            "change_count",
            "additions",
            "branch_matches",
            "title_matches",
            "author_matches",
            "label_exists",
            "file_path_matches",
            "has_changes_in",
            // CommonPredicates - new comparison predicates
            "deletions",
            "total_changes",
            "commit_count",
            "approval_count",
            "reviewer_count",
            "label_count",
            "description_length",
            // MrDataPredicates - bare
            "has_description",
            "has_approvals",
            "has_reviewers",
            "has_conflicts",
            "is_merged",
            "is_closed",
            "is_opened",
            "has_ci",
            // MrDataPredicates - function/regex
            "description_matches",
            "commit_message_matches",
            "source_branch_matches",
            "target_branch_matches",
            "ci_status",
            "ci_job_status",
            "change_type_exists",
            "file_extension_matches",
            "merge_status",
            "label_matches",
            "approved_by",
            "reviewer_is",
            "commit_author_matches",
            "file_modified",
            // FileMetricPredicates
            "file_count",
            "file_count_added",
            "file_count_deleted",
            "file_count_modified",
            "has_file_type",
            "file_added",
            // EnvironmentPredicates
            "is_ci",
            "is_gitlab_ci",
            "is_github_actions",
            "ci_platform",
            "env_set",
            "env_equals",
            "env_matches",
            // ConfigPredicates
            "is_dry_run",
            "has_profile",
            "profile_is",
            "module_configured",
            "mr_age_hours",
            "mr_age_days",
            "last_commit_age_hours",
        )

        for (name in expectedPredicates) {
            assertTrue(
                name in registered,
                "Expected predicate '$name' not found in registry. " +
                    "Registered: ${registered.sorted()}",
            )
        }
    }
}
