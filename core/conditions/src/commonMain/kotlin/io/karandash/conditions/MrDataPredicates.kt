@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.api.platform.MergeRequestField
import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequestState

/**
 * MR data predicates for condition expressions.
 *
 * Includes bare predicates for MR state checks (e.g., `has_description`,
 * `is_merged`) and function/regex predicates for matching MR fields
 * (e.g., `description_matches`, `commit_message_matches`).
 */
object MrDataPredicates {
    /**
     * Registers all MR data predicates into [registry].
     *
     * @param registry The predicate registry to register into.
     * @param regexCacheFn Optional cached regex function for regex-based predicates.
     */
    fun registerAll(
        registry: PredicateRegistry<ConditionContext>,
        regexCacheFn: ((String) -> Regex)? = null,
    ) {
        registry.register(HasDescriptionPredicate)
        registry.register(HasApprovalsPredicate)
        registry.register(HasReviewersPredicate)
        registry.register(HasConflictsPredicate)
        registry.register(IsMergedPredicate)
        registry.register(IsClosedPredicate)
        registry.register(IsOpenedPredicate)
        registry.register(HasCiPredicate)
        registry.register(DescriptionMatchesPredicate(regexCacheFn))
        registry.register(CommitMessageMatchesPredicate(regexCacheFn))
        registry.register(SourceBranchMatchesPredicate(regexCacheFn))
        registry.register(TargetBranchMatchesPredicate(regexCacheFn))
        registry.register(CiStatusPredicate)
        registry.register(CiJobStatusPredicate)
        registry.register(ChangeTypeExistsPredicate)
        registry.register(FileExtensionMatchesPredicate)
        registry.register(MergeStatusPredicate)
        registry.register(LabelMatchesPredicate(regexCacheFn))
        registry.register(ApprovedByPredicate)
        registry.register(ReviewerIsPredicate)
        registry.register(CommitAuthorMatchesPredicate(regexCacheFn))
        registry.register(FileModifiedPredicate)
    }
}

// ── Bare predicates ─────────────────────────────────────────────────────

private object HasDescriptionPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "has_description"
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = !context.mr.description.isNullOrBlank()
}

private object HasApprovalsPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "has_approvals"
    override val requiredField = MergeRequestField.APPROVALS
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.mr.approvals.isNotEmpty()
}

private object HasReviewersPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "has_reviewers"
    override val requiredField = MergeRequestField.REVIEWERS
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.mr.reviewers.isNotEmpty()
}

private object HasConflictsPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "has_conflicts"
    override val requiredField = MergeRequestField.CONFLICTS
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.mr.hasConflicts == true
}

private object IsMergedPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "is_merged"
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.mr.state == MergeRequestState.MERGED
}

private object IsClosedPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "is_closed"
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.mr.state == MergeRequestState.CLOSED
}

private object IsOpenedPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "is_opened"
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.mr.state == MergeRequestState.OPENED
}

private object HasCiPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "has_ci"
    override val requiredField = MergeRequestField.CI_STATUS
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.mr.ciStatus != null
}

// ── Regex predicates ────────────────────────────────────────────────────

private class DescriptionMatchesPredicate(
    private val regexCacheFn: ((String) -> Regex)?,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "description_matches"
    override val arity: Int = 1
    override val isRegexPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val pattern = args.getOrNull(0) ?: return false
        val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
        return regex.containsMatchIn(context.mr.description ?: "")
    }
}

private class CommitMessageMatchesPredicate(
    private val regexCacheFn: ((String) -> Regex)?,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "commit_message_matches"
    override val arity: Int = 1
    override val isRegexPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val pattern = args.getOrNull(0) ?: return false
        val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
        return context.mr.commits.any { regex.containsMatchIn(it.message) }
    }
}

private class SourceBranchMatchesPredicate(
    private val regexCacheFn: ((String) -> Regex)?,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "source_branch_matches"
    override val arity: Int = 1
    override val isRegexPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val pattern = args.getOrNull(0) ?: return false
        val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
        return regex.containsMatchIn(context.mr.sourceBranch)
    }
}

private class TargetBranchMatchesPredicate(
    private val regexCacheFn: ((String) -> Regex)?,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "target_branch_matches"
    override val arity: Int = 1
    override val isRegexPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val pattern = args.getOrNull(0) ?: return false
        val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
        return regex.containsMatchIn(context.mr.targetBranch)
    }
}

private class LabelMatchesPredicate(
    private val regexCacheFn: ((String) -> Regex)?,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "label_matches"
    override val requiredField = MergeRequestField.LABELS
    override val arity: Int = 1
    override val isRegexPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val pattern = args.getOrNull(0) ?: return false
        val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
        return context.mr.labels.any { regex.containsMatchIn(it) }
    }
}

private class CommitAuthorMatchesPredicate(
    private val regexCacheFn: ((String) -> Regex)?,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "commit_author_matches"
    override val arity: Int = 1
    override val isRegexPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val pattern = args.getOrNull(0) ?: return false
        val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
        return context.mr.commits.any { regex.containsMatchIn(it.author.email) }
    }
}

// ── Function predicates ─────────────────────────────────────────────────

private object CiStatusPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "ci_status"
    override val requiredField = MergeRequestField.CI_STATUS
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val status = args.getOrNull(0) ?: return false
        return context.mr.ciStatus?.equals(status, ignoreCase = true) == true
    }
}

private object CiJobStatusPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "ci_job_status"
    override val requiredField = MergeRequestField.CI_JOBS
    override val arity: Int = 2

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val jobName = args.getOrNull(0) ?: return false
        val status = args.getOrNull(1) ?: return false
        return context.mr.ciJobs.any {
            it.name == jobName && it.status.equals(status, ignoreCase = true)
        }
    }
}

private object ChangeTypeExistsPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "change_type_exists"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val type = args.getOrNull(0) ?: return false
        return context.mr.changes.any {
            it.changeType.name.equals(type, ignoreCase = true)
        }
    }
}

private object FileExtensionMatchesPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "file_extension_matches"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val ext = args.getOrNull(0) ?: return false
        return context.mr.changes.any { it.path.endsWith(ext) }
    }
}

private object MergeStatusPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "merge_status"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val status = args.getOrNull(0) ?: return false
        return context.mr.mergeStatus?.equals(status, ignoreCase = true) == true
    }
}

private object ApprovedByPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "approved_by"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val username = args.getOrNull(0) ?: return false
        return context.mr.approvals.any {
            it.user.username.equals(username, ignoreCase = true)
        }
    }
}

private object ReviewerIsPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "reviewer_is"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val username = args.getOrNull(0) ?: return false
        return context.mr.reviewers.any {
            it.username.equals(username, ignoreCase = true)
        }
    }
}

private object FileModifiedPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "file_modified"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val path = args.getOrNull(0) ?: return false
        return context.mr.changes.any { it.path == path }
    }
}
