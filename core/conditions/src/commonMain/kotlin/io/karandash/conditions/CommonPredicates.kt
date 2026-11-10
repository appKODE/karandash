@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.api.platform.MergeRequestField
import io.karandash.helpers.RegexHelper
import io.karandash.utils.GlobMatcher

/**
 * Common MR predicates available to all condition evaluators.
 *
 * Includes bare predicates (`is_draft`), comparison predicates
 * (`change_count`, `additions`), regex-matching predicates
 * (`branch_matches`, `title_matches`, `author_matches`),
 * and fundamental MR-domain predicates (`label_exists`,
 * `file_path_matches`, `has_changes_in`).
 *
 * `label_exists`, `file_path_matches` and `has_changes_in` have exactly one implementation, exposed as
 * [labelExists], [filePathMatches] and [hasChangesIn]: rule modules that also register them via
 * [Module.registerPredicates][io.karandash.api.Module.registerPredicates] register these same instances,
 * so registration order never changes their behavior.
 */
object CommonPredicates {
    /** `label_exists(name)`: the MR has a label equal to `name`, ignoring case. */
    val labelExists: ConditionPredicate<ConditionContext> = LabelExistsPredicate

    /** `file_path_matches(glob)`: any changed file path matches the path glob ([GlobMatcher.matches]). */
    val filePathMatches: ConditionPredicate<ConditionContext> = FilePathMatchesPredicate

    /** `has_changes_in(prefix)`: any changed file path starts with `prefix`. */
    val hasChangesIn: ConditionPredicate<ConditionContext> = HasChangesInPredicate

    /**
     * Registers all common predicates into [registry].
     *
     * @param regexCacheFn Optional cached regex function for regex-based predicates.
     */
    fun registerAll(
        registry: PredicateRegistry<ConditionContext>,
        regexCacheFn: ((String) -> Regex)? = null,
    ) {
        registry.register(IsDraftPredicate)
        registry.register(ChangeCountPredicate)
        registry.register(AdditionsPredicate)
        registry.register(DeletionsPredicate)
        registry.register(TotalChangesPredicate)
        registry.register(CommitCountPredicate)
        registry.register(ApprovalCountPredicate)
        registry.register(ReviewerCountPredicate)
        registry.register(LabelCountPredicate)
        registry.register(DescriptionLengthPredicate)
        registry.register(BranchMatchesPredicate(regexCacheFn))
        registry.register(TitleMatchesPredicate(regexCacheFn))
        registry.register(AuthorMatchesPredicate(regexCacheFn))
        registry.register(LabelExistsPredicate)
        registry.register(FilePathMatchesPredicate)
        registry.register(HasChangesInPredicate)
        MrDataPredicates.registerAll(registry, regexCacheFn)
        FileMetricPredicates.registerAll(registry)
        EnvironmentPredicates.registerAll(registry, regexCacheFn)
        ConfigPredicates.registerAll(registry)
    }
}

private object IsDraftPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "is_draft"
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val expected = args.getOrNull(0)?.toBooleanStrictOrNull() ?: true
        return context.mr.draft == expected
    }
}

private object ChangeCountPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "change_count"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        return ExpressionEvaluator.compareInt(context.mr.changes.size, op, value)
    }
}

private object AdditionsPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "additions"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        return ExpressionEvaluator.compareInt(context.mr.changes.sumOf { it.additions }, op, value)
    }
}

private object DeletionsPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "deletions"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        return ExpressionEvaluator.compareInt(context.mr.changes.sumOf { it.deletions }, op, value)
    }
}

private object TotalChangesPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "total_changes"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        val total = context.mr.changes.sumOf { it.additions + it.deletions }
        return ExpressionEvaluator.compareInt(total, op, value)
    }
}

private object CommitCountPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "commit_count"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        return ExpressionEvaluator.compareInt(context.mr.commits.size, op, value)
    }
}

private object ApprovalCountPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "approval_count"
    override val requiredField = MergeRequestField.APPROVALS
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        return ExpressionEvaluator.compareInt(context.mr.approvals.size, op, value)
    }
}

private object ReviewerCountPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "reviewer_count"
    override val requiredField = MergeRequestField.REVIEWERS
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        return ExpressionEvaluator.compareInt(context.mr.reviewers.size, op, value)
    }
}

private object LabelCountPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "label_count"
    override val requiredField = MergeRequestField.LABELS
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        return ExpressionEvaluator.compareInt(context.mr.labels.size, op, value)
    }
}

private object DescriptionLengthPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "description_length"
    override val arity: Int = 2
    override val isComparisonPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val op = compOpOrNull(args.getOrNull(0)) ?: return false
        val value = args.getOrNull(1)?.toIntOrNull() ?: return false
        val length = context.mr.description?.length ?: 0
        return ExpressionEvaluator.compareInt(length, op, value)
    }
}

private class BranchMatchesPredicate(
    private val regexCacheFn: ((String) -> Regex)?,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "branch_matches"
    override val arity: Int = -1
    override val isRegexPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val pattern = args.getOrNull(0) ?: return false
        val useTarget = args.getOrNull(1)?.lowercase() == "target"
        val branch = if (useTarget) context.mr.targetBranch else context.mr.sourceBranch
        val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
        return regex.containsMatchIn(branch)
    }
}

private class TitleMatchesPredicate(
    private val regexCacheFn: ((String) -> Regex)?,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "title_matches"
    override val arity: Int = 1
    override val isRegexPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val pattern = args.getOrNull(0) ?: return false
        val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
        return regex.containsMatchIn(context.mr.title)
    }
}

private class AuthorMatchesPredicate(
    private val regexCacheFn: ((String) -> Regex)?,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "author_matches"
    override val arity: Int = 1
    override val isRegexPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val pattern = args.getOrNull(0) ?: return false
        val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
        return regex.containsMatchIn(context.mr.author.username)
    }
}

private object LabelExistsPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "label_exists"
    override val requiredField = MergeRequestField.LABELS
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val label = args.getOrNull(0) ?: return false
        return context.mr.labels.any { it.equals(label, ignoreCase = true) }
    }
}

private object FilePathMatchesPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "file_path_matches"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val glob = args.getOrNull(0) ?: return false
        return context.mr.changes.any { GlobMatcher.matches(it.path, glob) }
    }
}

private object HasChangesInPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "has_changes_in"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext,
    ): Boolean {
        val prefix = args.getOrNull(0) ?: return false
        return context.mr.changes.any { it.path.startsWith(prefix) }
    }
}
