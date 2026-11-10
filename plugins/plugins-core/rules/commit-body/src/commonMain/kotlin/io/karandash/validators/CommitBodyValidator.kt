package io.karandash.validators

import io.karandash.helpers.RegexHelper
import io.karandash.models.Commit
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.patterns.PatternFix
import io.karandash.utils.finding.CommitFindingGroup
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.mr.shortSha

class CommitBodyValidator(
    private val config: CommitBodyConfig,
    patterns: PatternAwareCompiler? = null,
) {
    private companion object {
        val DEFAULT_EXPLANATION = CommitBodyText.EXPLANATION.en
        const val MATCH_ANY_LINE = "anyLine"

        /** A word the pattern starts with literally, e.g. `CHANGELOG` in `^CHANGELOG: .+`. */
        val LEAD_WORD = Regex("""^\^([A-Za-z][A-Za-z0-9_-]{2,})""")
    }

    /** What the body has to hold, taken from the configured pattern (the rule has no fixed prefix). */
    private val bodyHint: String = config.pattern?.let { "a line matching `$it`" } ?: "a body line"

    private val leadWord: String? = config.pattern?.let { LEAD_WORD.find(it)?.groupValues?.get(1) }

    /** What a failing body lacks: `no CHANGELOG line`, or `no line matching <pattern>` when the pattern has no leading word. */
    private val missingLine: String = leadWord?.let { "no $it line" } ?: "no line matching `${config.pattern}`"

    private val compiledPattern: Regex?
    private val patternError: Finding?
    private val subjectFilter: Regex?
    private val titleFilter: Regex?
    private val filterErrors: List<Finding>

    init {
        val errors = mutableListOf<Finding>()
        compiledPattern = config.pattern?.let { RegexHelper.tryCompile(patterns, it, CommitBodyRuleModule.RULE_ID, errors) }
        patternError = errors.firstOrNull()
        val compileErrors = mutableListOf<Finding>()
        subjectFilter = config.requiredWhenSubjectMatches?.let {
            RegexHelper.tryCompile(patterns, it, CommitBodyRuleModule.RULE_ID, compileErrors)
        }
        titleFilter = config.requiredWhenTitleMatches?.let {
            RegexHelper.tryCompile(patterns, it, CommitBodyRuleModule.RULE_ID, compileErrors)
        }
        filterErrors = compileErrors.toList()
    }

    fun validate(mr: MergeRequest): List<Finding> =
        filterErrors +
            when (config.scope) {
                CommitBodyConfig.SCOPE_ANY_COMMIT -> validateAnyCommit(mr)
                else -> validatePerCommit(mr)
            }

    private fun validatePerCommit(mr: MergeRequest): List<Finding> {
        val missing = mr.commits
            .filter { isRequiredFor(mr, it) && bodyLinesOf(it).isEmpty() }
            .map { commit ->
                val sha = commit.sha.shortSha()
                sha to
                    FindingFactory.error(
                        rule = CommitBodyRuleModule.RULE_ID,
                        message = "Commit body is required but missing in commit $sha",
                        suggestion = "Add a blank line after the commit title followed by $bodyHint.",
                        explanation = DEFAULT_EXPLANATION,
                    )
            }
        val missingFindings = CommitFindingGroup.collapse(missing, "are missing the required body")
        val checked = if (config.pattern == null) {
            emptyList()
        } else {
            mr.commits.filter { bodyLinesOf(it).isNotEmpty() && patternAppliesTo(mr, it) }
        }
        val regex = compiledPattern
        if (checked.isEmpty()) return missingFindings
        if (regex == null) return missingFindings + listOfNotNull(patternError)
        val mismatched = checked.mapNotNull { commit ->
            val bodyLines = bodyLinesOf(commit)
            if (matchesPattern(bodyLines, regex)) null else commit.sha.shortSha() to mismatch(commit.sha.shortSha(), bodyLines)
        }
        return missingFindings + CommitFindingGroup.collapse(mismatched, "have $missingLine in the body")
    }

    /**
     * The pattern is checked only on commits that need a body: a commit a `requiredWhen*` filter leaves out (say,
     * one without a ticket in its subject) may carry any body. With no filter configured, a body that is present
     * must match whether or not `required` is set.
     */
    private fun patternAppliesTo(
        mr: MergeRequest,
        commit: Commit,
    ): Boolean =
        isRequiredFor(mr, commit) ||
            (config.requiredWhenSubjectMatches == null && config.requiredWhenTitleMatches == null)

    /** The finding for a body that misses the pattern: it quotes the line that was checked and names the match mode. */
    private fun mismatch(
        sha: String,
        bodyLines: List<String>,
    ): Finding {
        val anyLine = config.matchMode == MATCH_ANY_LINE
        val lines = bodyLines.filter { it.isNotBlank() }
        val quoted = if (anyLine) closestLine(lines) else bodyLines.first()
        val checked = when {
            !anyLine -> {
                "first line \"$quoted\" does not match " +
                    "(checked: first line only, match-mode: ${config.matchMode}; set match-mode: anyLine to check every line)"
            }

            lines.size == 1 -> {
                "its only line \"$quoted\" does not match (match-mode: anyLine)"
            }

            else -> {
                "none of ${lines.size} lines match (match-mode: anyLine); closest: \"$quoted\""
            }
        }
        val fix = PatternFix.of(checkNotNull(config.pattern), quoted, subject = "body line", diagnose = !anyLine)
        val where = if (anyLine) "" else " as the first body line"
        return FindingFactory.error(
            rule = CommitBodyRuleModule.RULE_ID,
            message = "Commit $sha: $missingLine in the body; $checked",
            suggestion = fix.value?.let { "Add a line like \"$it\"$where." } ?: "Start the commit body with $bodyHint.",
            explanation = DEFAULT_EXPLANATION,
            metadata = fix.metadata(),
        )
    }

    /** The line that mentions the pattern's leading word (`CHANGELOG` for `^CHANGELOG: .+`), else the first one. */
    private fun closestLine(lines: List<String>): String =
        leadWord?.let { word -> lines.firstOrNull { it.contains(word, ignoreCase = true) } } ?: lines.first()

    private fun validateAnyCommit(mr: MergeRequest): List<Finding> {
        if (config.pattern != null && compiledPattern == null) {
            return listOfNotNull(patternError)
        }

        val candidates = mr.commits.filter { isRequiredFor(mr, it) }
        if (candidates.isEmpty()) return emptyList()

        val satisfied = candidates.any { commit ->
            val bodyLines = bodyLinesOf(commit)
            if (bodyLines.isEmpty()) return@any false
            if (config.pattern == null) return@any true
            val regex = compiledPattern ?: return@any false
            matchesPattern(bodyLines, regex)
        }
        if (satisfied) return emptyList()

        return listOf(
            FindingFactory.error(
                rule = CommitBodyRuleModule.RULE_ID,
                message = "No commit body in this MR contains a line matching the configured pattern.",
                suggestion = "Add $bodyHint to at least one commit body in this MR.",
                explanation = DEFAULT_EXPLANATION,
            )
        )
    }

    private fun bodyLinesOf(commit: Commit): List<String> {
        val messageLines = commit.message.lines()
        return if (messageLines.size > 1) messageLines.drop(1).dropWhile { it.isBlank() } else emptyList()
    }

    /**
     * Whether [commit] needs a body. Precedence, highest first: an MR title matching `requiredWhenTitleMatches`
     * requires a body on every commit; otherwise a configured `requiredWhenSubjectMatches` decides per commit (and
     * `required` is not consulted); otherwise `required`.
     */
    private fun isRequiredFor(
        mr: MergeRequest,
        commit: Commit
    ): Boolean {
        val subjectLine = commit.message.lines().first()
        val titleMatches = titleFilter?.containsMatchIn(mr.title) == true
        return when {
            titleMatches -> true
            subjectFilter != null -> subjectFilter.containsMatchIn(subjectLine)
            else -> config.required
        }
    }

    private fun matchesPattern(
        bodyLines: List<String>,
        regex: Regex
    ): Boolean =
        when (config.matchMode) {
            "contains" -> regex.containsMatchIn(bodyLines.first())
            MATCH_ANY_LINE -> bodyLines.any { regex.containsMatchIn(it) }
            else -> regex.matches(bodyLines.first()) // fullMatch (default)
        }
}
